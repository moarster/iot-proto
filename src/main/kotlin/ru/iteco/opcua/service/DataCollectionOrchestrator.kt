package ru.iteco.opcua.service

import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retry
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import ru.iteco.opcua.client.OpcUaDataCollector

@Service
class DataCollectionOrchestrator(
    private val opcUaDataCollector: OpcUaDataCollector,
    private val meterDataOrchestrationService: MeterDataOrchestrationService
) {
    private val logger = LoggerFactory.getLogger(DataCollectionOrchestrator::class.java)

    private val collectionScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @OptIn(DelicateCoroutinesApi::class)
    @EventListener(ApplicationReadyEvent::class)
    fun startDataCollection() {
        logger.info("Запускаем сбор данных из OPC UA...")

        collectionScope.launch {
            try {
                opcUaDataCollector.initialize()
                logger.info("Клиент OPC UA успешно инициализирован")

                opcUaDataCollector.getDataStream()
                    .onEach { rawData ->
                        logger.debug("Получены данные из OPC UA — узел: {}, значение: {}", rawData.nodeId, rawData.value)
                        meterDataOrchestrationService.processData(rawData)
                    }
                    .catch { error ->
                        logger.error("Произошла ошибка в потоке сбора данных", error)
                    }
                    .retry(3)
                    .launchIn(collectionScope)

            } catch (e: Exception) {
                logger.error("Не удалось запустить сбор данных из OPC UA", e)
            }
        }

        logger.info("Настройка сбора данных из OPC UA завершена")
    }

    @PreDestroy
    fun cleanup() {
        logger.info("Выключаем DataCollectionOrchestrator")
        collectionScope.cancel("Application shutdown")
    }
}