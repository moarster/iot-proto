package ru.iteco.opcua.client


import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.eclipse.milo.opcua.sdk.client.OpcUaClient
import org.eclipse.milo.opcua.sdk.client.api.config.OpcUaClientConfigBuilder
import org.eclipse.milo.opcua.sdk.client.api.subscriptions.UaMonitoredItem
import org.eclipse.milo.opcua.stack.client.DiscoveryClient
import org.eclipse.milo.opcua.stack.core.AttributeId
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue
import org.eclipse.milo.opcua.stack.core.types.builtin.LocalizedText
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId
import org.eclipse.milo.opcua.stack.core.types.builtin.QualifiedName
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UByte
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned
import org.eclipse.milo.opcua.stack.core.types.enumerated.ApplicationType
import org.eclipse.milo.opcua.stack.core.types.enumerated.MessageSecurityMode
import org.eclipse.milo.opcua.stack.core.types.enumerated.MonitoringMode
import org.eclipse.milo.opcua.stack.core.types.enumerated.TimestampsToReturn
import org.eclipse.milo.opcua.stack.core.types.structured.*
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import ru.iteco.opcua.config.Endpoint
import ru.iteco.opcua.config.NodeIdsConfig
import ru.iteco.opcua.config.OpcUaConfig
import ru.iteco.opcua.model.RawMeterData
import ru.iteco.opcua.service.MetadataResolver
import ru.iteco.opcua.service.MetadataService
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

@Service
class OpcUaDataCollector(
    private val connectionManager: OpcUaConnectionManager,
    private val metadataResolver: MetadataResolver,
    private val nodeIdsConfig: NodeIdsConfig,
    private val opcUaConfig: OpcUaConfig,
) {
    private val logger = LoggerFactory.getLogger(OpcUaDataCollector::class.java)

    private val dataChannel = Channel<RawMeterData>(Channel.UNLIMITED)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @PostConstruct
    fun initialize() {
        scope.launch {
            connectionManager.initializeConnections()
            startDataCollection()
        }
    }

    private suspend fun startDataCollection() {
        connectionManager.getConnectedClients().forEach { connection ->
            scope.launch {
                setupDataCollection(connection)
            }
        }
    }

    /**
     * Сначала пробуем подписаться, если не получается, то начинаем опрос
     */
    private suspend fun setupDataCollection(connection: OpcUaConnectionManager.ClientConnection) {
        try {
            if (!tryCreateSubscription(connection)) {
                logger.warn("Не удалось подписаться на ${connection.endpoint}, начинаем опрос")
                startPolling(connection)
            }
        } catch (e: Exception) {
            logger.error("Ошибки сбора данных с ${connection.endpoint}", e)
        }
    }

    private suspend fun tryCreateSubscription(connection: OpcUaConnectionManager.ClientConnection): Boolean = withContext(Dispatchers.IO) {
        try {
            val subscription = connection.client.subscriptionManager
                .createSubscription(opcUaConfig.subscriptionInterval.toDouble())
                .get()

            val nodeIdStrings = buildAllNodeIds(connection.endpoint)

            if (nodeIdStrings.values.all { it.isEmpty() }) {
                logger.warn("Ноды данных не сконфигурированы")
                return@withContext false
            }

            val monitoredItems = nodeIdStrings.values.flatten().map { nodeIdString ->
                val readValueId = ReadValueId(
                    NodeId.parse(nodeIdString),
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

            var successCount = 0
            createdItems.forEach { item ->
                if (item.statusCode.isGood) {
                    item.setValueConsumer { _, value ->
                        scope.launch {
                            handleDataChange(connection.endpoint.url, item, value)
                        }
                    }
                    successCount++
                } else {
                    logger.debug("Failed to monitor {} on {}", item.readValueId.nodeId, connection.endpoint)
                }
            }

            logger.debug("Created {}/{} monitored items for {}", successCount, monitoredItems.size, connection.endpoint)
            true
        } catch (e: Exception) {
            logger.error("Subscription creation failed for ${connection.endpoint}: ${e.message}")
            false
        }
    }

    private fun startPolling(connection: OpcUaConnectionManager.ClientConnection) {
        scope.launch {
            while (connection.isConnected) {
                try {
                    pollMeasurementNodes(connection)
                    delay(opcUaConfig.subscriptionInterval)
                } catch (e: Exception) {
                    logger.error("Polling error for ${connection.endpoint.url}", e)
                    delay(5000)
                }
            }
        }
    }

    private suspend fun pollMeasurementNodes(connection: OpcUaConnectionManager.ClientConnection) = withContext(Dispatchers.IO) {
        try {
            val measurementNodes = buildMeasurementNodeIds(connection.endpoint)
            if (measurementNodes.isEmpty()) {
                logger.warn("No measurement nodes configured for ${connection.endpoint}")
                return@withContext
            }

            val nodeIds = measurementNodes.map { NodeId.parse(it) }
            val readValueIds = nodeIds.map { nodeId ->
                ReadValueId(nodeId, AttributeId.Value.uid(), null, QualifiedName.NULL_VALUE)
            }

            val dataValues = connection.client.read(0.0, TimestampsToReturn.Both, readValueIds).get().results

            dataValues.forEachIndexed { index, dataValue ->
                if (dataValue.statusCode?.isGood == true) {
                    val nodeIdString = measurementNodes[index]
                    handlePolledData(connection.endpoint.url, nodeIdString, dataValue)
                }
            }

        } catch (e: Exception) {
            logger.error("Polling failed for ${connection.endpoint}", e)
            throw e
        }
    }
    /**
     * Builds only measurement node IDs (Current.* and History.*)
     */
    private fun buildMeasurementNodeIds(endpoint: Endpoint): List<String> {
        val nodeIds = mutableListOf<String>()
        val nodePrefix = "ns=2;s=GIUSController"

        endpoint.meters.forEach { meter ->
            meter.subs.forEach { sub ->
                // Current measurements
                nodeIdsConfig.current.forEach { valueNode ->
                    nodeIds.add("$nodePrefix.${meter.guid}.$sub.Current.$valueNode")
                }

                // Historical measurements
                nodeIdsConfig.history.forEach { valueNode ->
                    nodeIds.add("$nodePrefix.${meter.guid}.$sub.History.$valueNode")
                }
            }
        }

        return nodeIds
    }

    /**
     * Builds metadata node IDs for a specific endpoint
     */
    private fun buildMetadataNodeIds(endpoint: Endpoint): List<MetadataResolver.MetadataRequest> {
        val requests = mutableListOf<MetadataResolver.MetadataRequest>()
        val nodePrefix = "ns=2;s=GIUSController"

        // Controller metadata
        nodeIdsConfig.uspd.forEach { controllerNode ->
            requests.add(MetadataResolver.MetadataRequest(
                "$nodePrefix.$controllerNode",
                when (controllerNode) {
                    "GUID" -> MetadataResolver.MetadataType.USPD_GUID
                    else -> MetadataResolver.MetadataType.OTHER
                }
            ))
        }

        endpoint.meters.forEach { meter ->
            // Meter metadata
            nodeIdsConfig.meter.forEach { meterNode ->
                requests.add(MetadataResolver.MetadataRequest(
                    "$nodePrefix.${meter.guid}.$meterNode",
                    when (meterNode) {
                        "Model" -> MetadataResolver.MetadataType.METER_MODEL
                        "SerialNumber" -> MetadataResolver.MetadataType.METER_SERIAL
                        else -> MetadataResolver.MetadataType.OTHER
                    }
                ))
            }

            // Sub metadata
            meter.subs.forEach { sub ->
                nodeIdsConfig.sub.forEach { subNode ->
                    requests.add(MetadataResolver.MetadataRequest(
                        "$nodePrefix.${meter.guid}.$sub.$subNode",
                        when (subNode) {
                            "Address" -> MetadataResolver.MetadataType.SUB_ADDRESS
                            else -> MetadataResolver.MetadataType.OTHER
                        }
                    ))
                }
            }
        }

        return requests
    }

    private fun buildAllNodeIds(endpoint: Endpoint): Map<String, List<String>> {
        val nodeIds = mutableMapOf("controller" to mutableListOf<String>(),
                                   "meter" to mutableListOf<String>(),
                                   "sub" to mutableListOf<String>(),
                                   "current" to mutableListOf<String>(),
                                   "history" to mutableListOf<String>())
        val nodePrefix = "ns=2;s=GIUSController."

        // 1. Мета-данные контроллера
        nodeIdsConfig.uspd.forEach { controllerNode ->
            nodeIds["controller"]?.add(nodePrefix+controllerNode)
        }


        endpoint.meters.forEach { meter ->
            // 2. Мета-данные счетчика
            nodeIdsConfig.meter.forEach { meterNode ->
                nodeIds["meter"]?.add("$nodePrefix.${meter.guid}.$meterNode")
            }

            // 3. Мета-данные узла
            meter.subs.forEach { sub ->
                // 3. Мета-данные узла
                nodeIdsConfig.sub.forEach { subNode ->
                    nodeIds["sub"]?.add("$nodePrefix.${meter.guid}.$sub.$subNode")
                }

                // 3. Измерения
                nodeIdsConfig.current.forEach { valueNode ->
                    nodeIds["current"]?.add("$nodePrefix.${meter.guid}.$sub.Current.$valueNode")
                }
                nodeIdsConfig.history.forEach { valueNode ->
                    nodeIds["history"]?.add("$nodePrefix.${meter.guid}.$sub.History.$valueNode")
                }
            }
        }

        return nodeIds
    }

    private suspend fun handleDataChange(endpointUrl: String, item: UaMonitoredItem, value: DataValue) {
        try {
            val nodeId = item.readValueId.nodeId.toParseableString()
            val rawData = createRawMeterData(endpointUrl, nodeId, value)

            val sent = dataChannel.trySend(rawData)
            if (!sent.isSuccess) {
                logger.warn("Channel full, dropping data from $endpointUrl")
            }
        } catch (e: Exception) {
            logger.error("Error handling subscription data for $endpointUrl", e)
        }
    }

    private suspend fun handlePolledData(endpointUrl: String, nodeId: String, value: DataValue) {
        try {
            val rawData = createRawMeterData(endpointUrl, nodeId, value)
            val sent = dataChannel.trySend(rawData)
            if (!sent.isSuccess) {
                logger.warn("Channel full, dropping polled data from $endpointUrl")
            }
        } catch (e: Exception) {
            logger.error("Error handling polled data for $endpointUrl", e)
        }
    }

    private suspend fun createRawMeterData(endpointUrl: String, nodeId: String, value: DataValue): RawMeterData {
        // Parse the node structure to extract context
        val nodeContext = parseNodeId(nodeId)

        // Resolve metadata efficiently - batch load what we need
        val metadataKeys = determineRequiredMetadata(nodeContext)
        val metadata = metadataResolver.batchResolveMetadata(endpointUrl, metadataKeys)

        return RawMeterData(
            nodeId = nodeId,
            endpointUrl = endpointUrl,
            value = value.value?.value ?: "null",
            dataType = value.value?.dataType?.toString() ?: "unknown",
            quality = when {
                value.statusCode?.isGood ?: false -> "good"
                value.statusCode?.isBad ?: false -> "bad"
                value.statusCode?.isUncertain ?: false -> "uncertain"
                else -> "unknown"
            },
            timestamp = LocalDateTime.ofInstant(value.sourceTime?.javaInstant, ZoneId.of("Europe/Moscow")),
            serverTimestamp = LocalDateTime.ofInstant(value.serverTime?.javaInstant, ZoneId.of("Europe/Moscow")),
            uspdId = metadata["ns=2;s=GIUSController.GUID"]?.value?.toString() ?: "unknown",
            meterId = nodeContext.meterGuid ?: "unknown",
            subsystem = nodeContext.subAddress ?: "unknown",
            resourceType = determineResourceType(nodeId),
            isMeasurement = nodeContext.isMeasurement,
            unit = determineUnit(nodeId),
            periodStart = nodeContext.periodStart,
            periodEnd = nodeContext.periodEnd
        )
    }

    private fun parseNodeId(nodeId: String): NodeContext {
        // Parse: ns=2;s=GIUSController.{meterGuid}.{subAddress}.{Current|History}.{measurement}
        val parts = nodeId.removePrefix("ns=2;s=GIUSController.").split(".")

        return when {
            parts.size >= 4 && (parts[2] == "Current" || parts[2] == "History") -> {
                NodeContext(
                    meterGuid = parts[0],
                    subAddress = parts[1],
                    measurementType = parts[2],
                    measurementName = parts.getOrNull(3),
                    isMeasurement = true,
                    periodStart = if (parts[2] == "History") determineHistoryPeriod(parts) else null,
                    periodEnd = if (parts[2] == "History") determineHistoryPeriod(parts) else null
                )
            }
            else -> NodeContext(isMeasurement = false)
        }
    }

    private fun determineRequiredMetadata(nodeContext: NodeContext): List<MetadataResolver.MetadataRequest> {
        val requests = mutableListOf<MetadataResolver.MetadataRequest>()

        // Always need USPD GUID
        requests.add(MetadataResolver.MetadataRequest(
            "ns=2;s=GIUSController.GUID",
            MetadataResolver.MetadataType.USPD_GUID
        ))

        // If it's a measurement, we might need meter/sub metadata too
        if (nodeContext.isMeasurement && nodeContext.meterGuid != null) {
            requests.add(MetadataResolver.MetadataRequest(
                "ns=2;s=GIUSController.${nodeContext.meterGuid}.Model",
                MetadataResolver.MetadataType.METER_MODEL
            ))
        }

        return requests
    }

    private fun determineResourceType(nodeId: String): String {
        return when {
            nodeId.contains(".Current.") -> "current"
            nodeId.contains(".History.") -> "history"
            else -> {"controller"}
        }
    }

    // Public API methods
    fun getDataStream(): Flow<RawMeterData> = dataChannel.receiveAsFlow()

data class NodeContext(
    val meterGuid: String? = null,
    val subAddress: String? = null,
    val measurementType: String? = null,
    val measurementName: String? = null,
    val isMeasurement: Boolean = false,
    val periodStart: LocalDateTime? = null,
    val periodEnd: LocalDateTime? = null
)



}