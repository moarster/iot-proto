package ru.iteco.opcua.service

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retry
import kotlinx.coroutines.launch
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

        // Initialize the OPC UA client first to establish connection and subscriptions
        GlobalScope.launch { // Use GlobalScope.launch for the suspend function call
            try {
                opcUaClientService.initialize()
                logger.info("OPC UA Client initialized successfully.")

                // Once initialized, start collecting data from the stream
                opcUaClientService.getDataStream()
                    .onEach { rawData ->
                        logger.debug("Received data from OPC UA: {}. Value: {}", rawData.nodeId, rawData.value)
                        // Process the raw data in parallel operations (Mongo, PostgreSQL, Kafka)
                        dataProcessingService.processData(rawData)
                    }
                    .catch { error ->
                        logger.error("Error in data collection stream", error)
                    }
                    .retry(3) // Retry connection/stream errors 3 times
                    .launchIn(GlobalScope) // Launch the flow in GlobalScope to keep it running
            } catch (e: Exception) {
                logger.error("Failed to start OPC UA data collection due to initialization error", e)
            }
        }

        logger.info("OPC UA data collection orchestrator setup initiated.")
    }
}