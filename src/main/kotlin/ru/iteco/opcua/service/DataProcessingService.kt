package ru.iteco.opcua.service

import com.fasterxml.jackson.databind.ObjectMapper
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

    // Alternative fire-and-forget version if you don't need to wait for completion
    fun processDataAsync(rawData: RawMeterData) {
        // Launch all three operations independently without waiting
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

    private suspend fun saveToMongo(rawData: RawMeterData): RawMeterData {
        return rawMeterDataRepository.save(rawData).awaitSingle()
    }


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

    // Clean up resources when service is destroyed
    fun cleanup() {
        ioScope.cancel()
    }
}