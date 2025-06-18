package ru.iteco.opcua.client


import jakarta.annotation.PreDestroy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withContext
import org.eclipse.milo.opcua.sdk.client.OpcUaClient
import org.eclipse.milo.opcua.sdk.client.api.config.OpcUaClientConfigBuilder
import org.eclipse.milo.opcua.sdk.client.api.subscriptions.UaMonitoredItem
import org.eclipse.milo.opcua.stack.core.AttributeId
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue
import org.eclipse.milo.opcua.stack.core.types.builtin.LocalizedText
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId
import org.eclipse.milo.opcua.stack.core.types.builtin.QualifiedName
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned
import org.eclipse.milo.opcua.stack.core.types.enumerated.MonitoringMode
import org.eclipse.milo.opcua.stack.core.types.enumerated.TimestampsToReturn
import org.eclipse.milo.opcua.stack.core.types.structured.EndpointDescription
import org.eclipse.milo.opcua.stack.core.types.structured.MonitoredItemCreateRequest
import org.eclipse.milo.opcua.stack.core.types.structured.MonitoringParameters
import org.eclipse.milo.opcua.stack.core.types.structured.ReadValueId
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import ru.iteco.opcua.config.OpcUaConfig
import ru.iteco.opcua.model.RawMeterData
import java.time.LocalDateTime
import java.time.ZoneId

@Service
class OpcUaClientService(
    private val opcUaConfig: OpcUaConfig
) {
    private val logger = LoggerFactory.getLogger(OpcUaClientService::class.java)
    private lateinit var client: OpcUaClient
    private val dataChannel = Channel<RawMeterData>(Channel.UNLIMITED)


    suspend fun initialize() = withContext(Dispatchers.IO) {
        try {
            val config = OpcUaClientConfigBuilder()
                .setApplicationName(LocalizedText(opcUaConfig.applicationName))
                .setApplicationUri("urn:spring-boot:opcua:client")
                .setEndpoint(
                    EndpointDescription.builder()
                        .endpointUrl(opcUaConfig.endpointUrl)
                        .build()
                )
                .build()

            client = OpcUaClient.create(config)
            client.connect().get()

            createSubscription()

            logger.info("OPC UA Client connected to ${opcUaConfig.endpointUrl}")
        } catch (e: Exception) {
            logger.error("Failed to initialize OPC UA client", e)
        }
    }

    private fun createSubscription() {
        try {
            val subscription = client.subscriptionManager
                .createSubscription(opcUaConfig.subscriptionInterval.toDouble())
                .get()

            val monitoredItems = opcUaConfig.nodeIds.map { nodeId ->
                val readValueId = ReadValueId(
                    NodeId.parse(nodeId),
                    AttributeId.Value.uid(),
                    null,
                    QualifiedName.NULL_VALUE
                )

                val monitoringParameters = MonitoringParameters(
                    Unsigned.uint(1),
                    opcUaConfig.subscriptionInterval.toDouble(),
                    null,
                    Unsigned.uint(10),
                    true
                )

                MonitoredItemCreateRequest(
                    readValueId,
                    MonitoringMode.Reporting,
                    monitoringParameters
                )
            }

            val createdItems = subscription.createMonitoredItems(
                TimestampsToReturn.Both,
                monitoredItems
            ).get()

            createdItems.forEach { item ->
                item.setValueConsumer { _, value ->
                    handleDataChange(item, value)
                }
            }

            logger.info("Created ${createdItems.size} monitored items")
        } catch (e: Exception) {
            logger.error("Failed to create subscription", e)
        }
    }

    private fun handleDataChange(item: UaMonitoredItem, value: DataValue) {
        try {
            val nodeId = item.readValueId.nodeId.toParseableString()
            val rawData = RawMeterData(
                nodeId = nodeId,
                value = value.value.value ?: "null",
                dataType = value.value.dataType?.toString() ?: "unknown",
                quality = when {
                    value.statusCode?.isGood ?: false -> "good"
                    value.statusCode?.isBad ?: false -> "bad"
                    value.statusCode?.isUncertain ?: false -> "uncertain"
                    else -> "unknown"
                },
                timestamp = LocalDateTime.now(),
                serverTimestamp = value.serverTime?.javaInstant?.atZone(ZoneId.of("Europe/Moscow"))?.toLocalDateTime()
            )

            dataChannel.trySend(rawData)
            logger.debug("Data received from node {}: {}", nodeId, value.value.value)
        } catch (e: Exception) {
            logger.error("Error handling data change", e)
        }
    }

    fun getDataStream(): Flow<RawMeterData> = dataChannel.receiveAsFlow()

    @PreDestroy
    fun cleanup() {
        try {
            client.disconnect().get()
            logger.info("OPC UA Client disconnected")
        } catch (e: Exception) {
            logger.error("Error during cleanup", e)
        }
    }
}