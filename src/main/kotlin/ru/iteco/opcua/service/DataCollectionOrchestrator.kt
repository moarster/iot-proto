package ru.iteco.opcua.service

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retry
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import ru.iteco.opcua.client.OpcUaClientService

@Service
class DataCollectionOrchestrator(
    private val opcUaClientService: OpcUaClientService,
    private val dataProcessingService: DataProcessingService
) {
    private val logger = LoggerFactory.getLogger(DataCollectionOrchestrator::class.java)

    @OptIn(DelicateCoroutinesApi::class)
    @EventListener(ApplicationReadyEvent::class)
    fun startDataCollection() {
        logger.info("Starting OPC UA data collection...")

        opcUaClientService.getDataStream()
            .onEach { rawData ->
                logger.debug("Received data from OPC UA: {}", rawData.nodeId)
                dataProcessingService.processData(rawData)
            }
            .catch { error ->
                logger.error("Error in data collection stream", error)
            }
            .retry(3)
            .launchIn(GlobalScope)

        logger.info("OPC UA data collection started")
    }
}