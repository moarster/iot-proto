package ru.iteco.opcua.service

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.*
import kotlinx.coroutines.reactor.awaitSingle
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Service
import ru.iteco.opcua.model.MeterTimeSeries
import ru.iteco.opcua.model.RawMeterData
import ru.iteco.opcua.repository.MeterTimeSeriesRepository
import ru.iteco.opcua.repository.RawMeterDataRepository


@Service
class DataProcessingService(
    private val rawMeterDataRepository: RawMeterDataRepository,
    private val meterTimeSeriesRepository: MeterTimeSeriesRepository,
    private val dataTransformationService: DataTransformationService,
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val objectMapper: ObjectMapper
) {
    private val logger = LoggerFactory.getLogger(DataProcessingService::class.java)
    private val kafkaTopic = "meter-readings"

    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Processes raw meter data by saving it to MongoDB, transforming and saving to PostgreSQL,
     * and transforming and sending to Kafka, all concurrently.
     * @param rawData The raw meter data received from OPC UA.
     */
    suspend fun processData(rawData: RawMeterData) {
        // Process in three parallel coroutines
        coroutineScope {
            // Launch all three operations concurrently
            val mongoDeferred = async { saveToMongo(rawData) }
            val postgresDeferred = async { transformAndSaveToPostgres(rawData) }
            val kafkaDeferred = async { transformAndSendToKafka(rawData) }

            // Handle each result independently
            launch {
                try {
                    val savedData = mongoDeferred.await()
                    logger.debug("Saved raw data to MongoDB: {}", savedData.id)
                } catch (error: Exception) {
                    logger.error("Error saving to MongoDB", error)
                }
            }

            launch {
                try {
                    val savedTimeSeries = postgresDeferred.await()
                    if (savedTimeSeries != null) {
                        logger.debug("Saved timeseries data to PostgreSQL: {}", savedTimeSeries.id)
                    }
                } catch (error: Exception) {
                    logger.error("Error saving to PostgreSQL", error)
                }
            }

            launch {
                try {
                    kafkaDeferred.await()
                    logger.debug("Sent message to Kafka")
                } catch (error: Exception) {
                    logger.error("Error sending to Kafka", error)
                }
            }
        }
    }

    /**
     * Alternative fire-and-forget version if you don't need to wait for completion of all steps
     * within the `processData` call itself. This is less strict about error propagation
     * back to the caller of `processDataAsync`.
     * @param rawData The raw meter data received from OPC UA.
     */
    fun processDataAsync(rawData: RawMeterData) {
        // Launch all three operations independently without waiting for their completion within this function
        ioScope.launch {
            try {
                val savedData = saveToMongo(rawData)
                logger.debug("Saved raw data to MongoDB: {}", savedData.id)
            } catch (error: Exception) {
                logger.error("Error saving to MongoDB", error)
            }
        }

        ioScope.launch {
            try {
                val savedTimeSeries = transformAndSaveToPostgres(rawData)
                if (savedTimeSeries != null) {
                    logger.debug("Saved timeseries data to PostgreSQL: {}", savedTimeSeries.id)
                }
            } catch (error: Exception) {
                logger.error("Error saving to PostgreSQL", error)
            }
        }

        ioScope.launch {
            try {
                transformAndSendToKafka(rawData)
                logger.debug("Sent message to Kafka")
            } catch (error: Exception) {
                logger.error("Error sending to Kafka", error)
            }
        }
    }

    /**
     * Saves the raw meter data to MongoDB.
     * @param rawData The raw meter data to save.
     * @return The saved RawMeterData object.
     */
    private suspend fun saveToMongo(rawData: RawMeterData): RawMeterData {
        return rawMeterDataRepository.save(rawData).awaitSingle()
    }

    /**
     * Transforms raw data into time series data and saves it to PostgreSQL.
     * Assumes `dataTransformationService.transformToTimeSeries` performs the necessary CPU-bound transformation.
     * @param rawData The raw meter data to transform.
     * @return The saved MeterTimeSeries object, or null if transformation yields no data.
     */
    private suspend fun transformAndSaveToPostgres(rawData: RawMeterData):  MeterTimeSeries? {
        return withContext(Dispatchers.Default) {
            // Transform on Default dispatcher (CPU-bound work)
            dataTransformationService.transformToTimeSeries(rawData)
        }?.let { timeSeries ->
            // Save on IO dispatcher (database operation)
            withContext(Dispatchers.IO) {
                meterTimeSeriesRepository.save(timeSeries).awaitSingle()
            }
        }
    }

    /**
     * Transforms raw data into a Kafka message and sends it to Kafka.
     * Assumes `dataTransformationService.transformToKafkaMessage` performs the necessary CPU-bound transformation.
     * @param rawData The raw meter data to transform.
     */
    private suspend fun transformAndSendToKafka(rawData: RawMeterData) {
        val kafkaMessage = withContext(Dispatchers.Default) {
            // Transform on Default dispatcher (CPU-bound work)
            dataTransformationService.transformToKafkaMessage(rawData)
        }

        kafkaMessage?.let { message ->
            withContext(Dispatchers.IO) {
                // Kafka send on IO dispatcher (network operation)
                val messageJson = objectMapper.writeValueAsString(message)
                kafkaTemplate.send(kafkaTopic, message.deviceId, messageJson)
            }
        }
    }

    /**
     * Cancels the coroutine scope used for async operations,
     * ensuring proper resource cleanup when the service is destroyed.
     */
    @PreDestroy
    fun cleanup() {
        logger.info("Shutting down DataProcessingService: Cancelling ioScope.")
        ioScope.cancel()
    }
}