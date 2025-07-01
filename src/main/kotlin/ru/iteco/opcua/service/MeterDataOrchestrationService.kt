package ru.iteco.opcua.service


import jakarta.annotation.PreDestroy
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import ru.iteco.opcua.model.MeterDataEvent
import ru.iteco.opcua.model.RawMeterData
import ru.iteco.opcua.service.enrichment.DataEnrichmentService
import ru.iteco.opcua.service.messaging.KafkaMessagingService
import ru.iteco.opcua.service.persistence.MongoDataPersistenceService
import ru.iteco.opcua.service.persistence.PostgresDataPersistenceService
import kotlin.time.Duration.Companion.seconds

@Service
class MeterDataOrchestrationService(
    private val dataEnrichmentService: DataEnrichmentService,
    private val mongoService: MongoDataPersistenceService,
    private val postgresService: PostgresDataPersistenceService,
    private val kafkaService: KafkaMessagingService
) {
    private val logger = LoggerFactory.getLogger(MeterDataOrchestrationService::class.java)

    // Используем конкретный диспетчер для IO операций + SupervisorJob для изоляции ошибок
    private val processingScope = CoroutineScope(
        Dispatchers.IO + SupervisorJob() + CoroutineName("MeterDataProcessor")
    )

    /**
     * Асинхронно обрабатывает данные счетчика
     * Не блокирует вызывающий поток, обработка идет в фоне
     */
    fun processData(rawData: RawMeterData) {
        processingScope.launch {
            try {
                logger.debug("Начинаем обработку данных для узла: {}", rawData.nodeId)

                val event = enrichData(rawData)
                persistData(event)

                logger.debug("Обработка данных для узла {} завершена успешно", rawData.nodeId)
            } catch (e: Exception) {
                logger.error("Ошибка при обработке данных счетчика для узла: {}", rawData.nodeId, e)
                // TODO: добавить метрики для мониторинга ошибок
            }
        }
    }

    /**
     * Параллельно обогащает данные для всех систем хранения
     * Использует coroutineScope для конкурентности
     */
    private suspend fun enrichData(rawData: RawMeterData): MeterDataEvent {
        return coroutineScope {
            logger.debug("Запускаем параллельное обогащение данных для узла: {}", rawData.nodeId)

            // Запускаем все операции обогащения параллельно

            val mongoDeferred = async(Dispatchers.Default + CoroutineName("MongoEnrichment")) {
                dataEnrichmentService.enrichForMongo(rawData)
            }
            val timescaleDeferred = async(Dispatchers.Default + CoroutineName("TimescaleEnrichment")) {
                dataEnrichmentService.enrichForTimescale(rawData)
            }
            val kafkaDeferred = async(Dispatchers.Default + CoroutineName("KafkaEnrichment")) {
                dataEnrichmentService.enrichForKafka(rawData)
            }

            MeterDataEvent(
                rawData = rawData,
                enrichedForMongo = mongoDeferred.await(),
                enrichedForTimescale = timescaleDeferred.await(),
                enrichedForKafka = kafkaDeferred.await()
            ).also {
                logger.debug("Обогащение данных для узла {} завершено", rawData.nodeId)
            }
        }
    }

    /**
     * Сохраняет данные во всех системах независимо
     * Ошибка в одной системе не влияет на другие
     */
    private fun persistData(event: MeterDataEvent) {
        logger.debug("Запускаем сохранение данных для узла: {}", event.rawData.nodeId)

        // Сохраняем в MongoDB
        event.enrichedForMongo?.let { mongoData ->
            processingScope.launch(CoroutineName("MongoSave-${event.rawData.nodeId}")) {
                mongoService.save(mongoData).onFailure { error ->
                    logger.error(
                        "Ошибка сохранения в MongoDB для узла: {} (timestamp: {})",
                        event.rawData.nodeId,
                        event.rawData.timestamp,
                        error
                    )
                    // TODO: добавить retry логику для критичных данных
                }
            }
        } ?: logger.warn("Отсутствуют данные для сохранения в MongoDB для узла: {}", event.rawData.nodeId)

        // Сохраняем в TimescaleDB (PostgreSQL)
        event.enrichedForTimescale?.let { timescaleData ->
            processingScope.launch(CoroutineName("PostgresSave-${event.rawData.nodeId}")) {
                postgresService.save(timescaleData).onFailure { error ->
                    logger.error(
                        "Ошибка сохранения в PostgreSQL для узла: {} (timestamp: {})",
                        event.rawData.nodeId,
                        event.rawData.timestamp,
                        error
                    )
                }
            }
        } ?: logger.warn("Отсутствуют данные для сохранения в PostgreSQL для узла: {}", event.rawData.nodeId)

        // Отправляем в Kafka
        event.enrichedForKafka?.let { kafkaData ->
            processingScope.launch(CoroutineName("KafkaSend-${event.rawData.nodeId}")) {
                kafkaService.send(kafkaData).onFailure { error ->
                    logger.error(
                        "Ошибка отправки в Kafka для узла: {} (timestamp: {})",
                        event.rawData.nodeId,
                        event.rawData.timestamp,
                        error
                    )
                }
            }
        } ?: logger.warn("Отсутствуют данные для отправки в Kafka для узла: {}", event.rawData.nodeId)
    }

    @PreDestroy
    fun cleanup() {
        logger.info("Начинаем остановку MeterDataOrchestrationService")

        try {
            // Отменяем все активные корутины
            processingScope.cancel("Остановка сервиса")

            // Даем время на корректное завершение операций
            runBlocking {
                withTimeoutOrNull(30.seconds) {
                    processingScope.coroutineContext[Job]?.join()
                }
            }

            logger.info("MeterDataOrchestrationService остановлен успешно")
        } catch (e: Exception) {
            logger.error("Ошибка при остановке MeterDataOrchestrationService", e)
        }
    }
}