package ru.iteco.opcua.client


import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eclipse.milo.opcua.sdk.client.OpcUaClient
import org.eclipse.milo.opcua.sdk.client.api.config.OpcUaClientConfigBuilder
import org.eclipse.milo.opcua.sdk.client.api.subscriptions.UaMonitoredItem
import org.eclipse.milo.opcua.stack.client.DiscoveryClient
import org.eclipse.milo.opcua.stack.core.AttributeId
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue
import org.eclipse.milo.opcua.stack.core.types.builtin.LocalizedText
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId
import org.eclipse.milo.opcua.stack.core.types.builtin.QualifiedName
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned
import org.eclipse.milo.opcua.stack.core.types.enumerated.ApplicationType
import org.eclipse.milo.opcua.stack.core.types.enumerated.MessageSecurityMode
import org.eclipse.milo.opcua.stack.core.types.enumerated.MonitoringMode
import org.eclipse.milo.opcua.stack.core.types.enumerated.TimestampsToReturn
import org.eclipse.milo.opcua.stack.core.types.structured.ApplicationDescription
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
    private var isConnected = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @PostConstruct
    fun initializeAsync() {
        scope.launch {
            initialize()
        }
    }
    suspend fun initialize() = withContext(Dispatchers.IO) {
        try {
            logger.info("Initializing OPC UA client...")
            logger.info("Endpoint URL: '${opcUaConfig.endpointUrl}'")
            logger.info("Application Name: '${opcUaConfig.applicationName}'")
            logger.info("Node IDs: ${opcUaConfig.nodeIds}")

            if (opcUaConfig.endpointUrl.isNullOrBlank()) {
                throw IllegalArgumentException("Endpoint URL is null or empty")
            }

            // First, let's discover endpoints from the server
            val endpoints = try {
                //throw Exception()
                DiscoveryClient.getEndpoints(opcUaConfig.endpointUrl).get()
            } catch (e: Exception) {
                logger.error("Failed to discover endpoints, using manual endpoint creation", e)
                // Fallback: create a basic endpoint manually
                listOf(
                    EndpointDescription(
                        opcUaConfig.endpointUrl,
                        ApplicationDescription(
                            "urn:bt6000:UnifiedAutomation:UaTeploServer",
                            "urn:dummy:opcua:server:product",
                            LocalizedText("en", "Dummy OPC UA Server"),
                            ApplicationType.Server,
                            null,
                            null,
                            emptyArray()
                        ),
                       null,
                       MessageSecurityMode.None,
                       "http://opcfoundation.org/UA/SecurityPolicy#None",
                       emptyArray(),
                      "http://opcfoundation.org/UA-Profile/Transport/uatcp-uasc-uabinary",
                        org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UByte.MIN
                    )
                )
            }
            val rewrittenEndpoints = endpoints.map { original ->
                EndpointDescription(
                    opcUaConfig.endpointUrl,
                    original.server,
                    original.serverCertificate,
                    original.securityMode,
                    original.securityPolicyUri,
                    original.userIdentityTokens,
                    original.transportProfileUri,
                    original.securityLevel
                )
            }
            val endpoint = rewrittenEndpoints.find {
                it.securityMode == MessageSecurityMode.None
            } ?: endpoints.firstOrNull()

            if (endpoint == null) {
                throw IllegalStateException("No suitable endpoint found")
            }

            logger.info("Using endpoint: ${endpoint.endpointUrl} with security: ${endpoint.securityMode}")

            val config = OpcUaClientConfigBuilder()
                .setApplicationName(LocalizedText(opcUaConfig.applicationName))
                .setApplicationUri("urn:spring-boot:opcua:client")
                .setEndpoint(endpoint)
                .build()

            client = OpcUaClient.create(config)

            logger.info("Connecting to OPC UA server: ${opcUaConfig.endpointUrl}")
            val connectFuture = client.connect()
            connectFuture.get() // This will throw if connection fails

            isConnected = true
            logger.info("Successfully connected to OPC UA server")

            // Try subscription first, fall back to polling if it fails
            if (!tryCreateSubscription()) {
                logger.warn("Subscription failed, falling back to polling")
                startPolling()
            }

        } catch (e: Exception) {
            logger.error("Failed to initialize OPC UA client: ${e.message}", e)
            isConnected = false

            reconnectAfterDelay()
        }
    }

    private fun tryCreateSubscription(): Boolean {
        return try {
            logger.info("Attempting to create subscription...")

            val subscription = client.subscriptionManager
                .createSubscription(opcUaConfig.subscriptionInterval.toDouble())
                .get()

            val monitoredItems = opcUaConfig.nodeIds.map { nodeId ->
                logger.debug("Creating monitored item for node: $nodeId")

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
                if (item.statusCode.isGood) {
                    item.setValueConsumer { _, value ->
                        handleDataChange(item, value)
                    }
                    logger.debug("Successfully created monitored item for: ${item.readValueId.nodeId}")
                } else {
                    logger.error("Failed to create monitored item for: ${item.readValueId.nodeId}, status: ${item.statusCode}")
                }
            }

            logger.info("Successfully created ${createdItems.count { it.statusCode.isGood }} monitored items")
            true
        } catch (e: Exception) {
            logger.error("Failed to create subscription: ${e.message}", e)
            false
        }
    }

    private fun startPolling() {
        logger.info("Starting periodic polling every ${opcUaConfig.subscriptionInterval}ms")

        scope.launch {
            while (isConnected) {
                try {
                    pollNodes()
                    delay(opcUaConfig.subscriptionInterval)
                } catch (e: Exception) {
                    logger.error("Error during polling", e)
                    delay(5000) // Wait longer on error
                }
            }
        }
    }

    private suspend fun pollNodes() = withContext(Dispatchers.IO) {
        try {
            val nodeIds = opcUaConfig.nodeIds.map { NodeId.parse(it) }
            val readValueIds = nodeIds.map { nodeId ->
                ReadValueId(nodeId, AttributeId.Value.uid(), null, QualifiedName.NULL_VALUE)
            }

            val dataValues = client.read(0.0, TimestampsToReturn.Both, readValueIds).get().results

            dataValues.forEachIndexed { index, dataValue ->
                if (dataValue.statusCode?.isGood == true) {
                    val nodeId = opcUaConfig.nodeIds[index]
                    handlePolledData(nodeId, dataValue)
                } else {
                    logger.warn("Bad quality data for node ${opcUaConfig.nodeIds[index]}: ${dataValue.statusCode}")
                }
            }

            logger.debug("Polled ${dataValues.size} nodes")
        } catch (e: Exception) {
            logger.error("Error polling nodes", e)
        }
    }

    private fun handlePolledData(nodeId: String, value: DataValue) {
        try {
            val rawData = createRawMeterData(nodeId, value)
            val sent = dataChannel.trySend(rawData)
            if (sent.isSuccess) {
                logger.debug("Polled data from node {}: {}", nodeId, value.value.value)
            } else {
                logger.warn("Failed to send polled data to channel for node: $nodeId")
            }
        } catch (e: Exception) {
            logger.error("Error handling polled data for node $nodeId", e)
        }
    }

    private fun handleDataChange(item: UaMonitoredItem, value: DataValue) {
        try {
            val nodeId = item.readValueId.nodeId.toParseableString()
            val rawData = createRawMeterData(nodeId, value)

            val sent = dataChannel.trySend(rawData)
            if (sent.isSuccess) {
                logger.debug("Subscription data received from node {}: {}", nodeId, value.value.value)
            } else {
                logger.warn("Failed to send subscription data to channel for node: $nodeId")
            }
        } catch (e: Exception) {
            logger.error("Error handling subscription data change", e)
        }
    }

    private fun createRawMeterData(nodeId: String, value: DataValue): RawMeterData {
        return RawMeterData(
            nodeId = nodeId,
            value = value.value?.value ?: "null",
            dataType = value.value?.dataType?.toString() ?: "unknown",
            quality = when {
                value.statusCode?.isGood ?: false -> "good"
                value.statusCode?.isBad ?: false -> "bad"
                value.statusCode?.isUncertain ?: false -> "uncertain"
                else -> "unknown"
            },
            timestamp = LocalDateTime.now(),
            serverTimestamp = value.serverTime?.javaInstant?.atZone(ZoneId.of("Europe/Moscow"))?.toLocalDateTime()
        )
    }

    private fun reconnectAfterDelay() {
        scope.launch {
            delay(10000) // Wait 10 seconds before retry
            logger.info("Attempting to reconnect...")
            initialize()
        }
    }

    fun getDataStream(): Flow<RawMeterData> = dataChannel.receiveAsFlow()

    fun isClientConnected(): Boolean = isConnected

    // Method to test connection by reading a single node
    suspend fun testConnection(): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!isConnected) return@withContext false

            val testNodeId = NodeId.parse(opcUaConfig.nodeIds.first())
            val readValueId = ReadValueId(testNodeId, AttributeId.Value.uid(), null, QualifiedName.NULL_VALUE)

            val result = client.read(0.0, TimestampsToReturn.Both, listOf(readValueId)).get().results
            val success = result.firstOrNull()?.statusCode?.isGood ?: false

            logger.info("Connection test result: $success")
            success
        } catch (e: Exception) {
            logger.error("Connection test failed", e)
            false
        }
    }

    @PreDestroy
    fun cleanup() {
        try {
            isConnected = false
            scope.cancel()
            if (::client.isInitialized) {
                client.disconnect().get()
                logger.info("OPC UA Client disconnected")
            }
        } catch (e: Exception) {
            logger.error("Error during cleanup", e)
        }
    }
}