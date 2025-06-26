package ru.iteco.opcua.client


import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
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
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned
import org.eclipse.milo.opcua.stack.core.types.enumerated.ApplicationType
import org.eclipse.milo.opcua.stack.core.types.enumerated.MessageSecurityMode
import org.eclipse.milo.opcua.stack.core.types.enumerated.MonitoringMode
import org.eclipse.milo.opcua.stack.core.types.enumerated.TimestampsToReturn
import org.eclipse.milo.opcua.stack.core.types.structured.*
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import ru.iteco.opcua.config.NodeIdsConfig
import ru.iteco.opcua.config.OpcUaConfig
import ru.iteco.opcua.model.RawMeterData
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

@Service
class OpcUaClientService(
    private val nodeIdsConfig: NodeIdsConfig,
    private val opcUaConfig: OpcUaConfig
) {
    private val logger = LoggerFactory.getLogger(OpcUaClientService::class.java)


    // Connection management
    private val clients = ConcurrentHashMap<String, ClientConnection>()
    private val dataChannel = Channel<RawMeterData>(Channel.UNLIMITED)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Connection pooling - limit concurrent connections to avoid overwhelming servers
    private val maxConcurrentConnections = 50
    private val connectionSemaphore = kotlinx.coroutines.sync.Semaphore(maxConcurrentConnections)

    // Stats tracking
    private val connectedCount = AtomicInteger(0)
    private val totalEndpoints = AtomicInteger(0)

    data class ClientConnection(
        val client: OpcUaClient,
        val endpoint: OpcUaConfig.Endpoint,
        @Volatile var isConnected: Boolean = false,
        @Volatile var lastReconnectAttempt: Long = 0,
        var reconnectJob: Job? = null
    )

    @PostConstruct
    fun initializeAsync() {
        scope.launch {
            initialize()
        }
    }
    suspend fun initialize() {
        logger.info("Initializing OPC UA multi-client service for ${opcUaConfig.endpoints.size} endpoints")
        totalEndpoints.set(opcUaConfig.endpoints.size)

        if (opcUaConfig.endpoints.isEmpty()) {
            logger.warn("No endpoints configured")
            return
        }

        // Start connections in batches to avoid overwhelming the network
        val batchSize = 10
        opcUaConfig.endpoints.chunked(batchSize).forEach { batch ->
            batch.forEach { endpoint ->
                scope.launch {
                    connectToEndpoint(endpoint)
                }
            }
            // Small delay between batches
            delay(1000)
        }

        // Start connection monitor
        startConnectionMonitor()
    }
    private suspend fun connectToEndpoint(endpoint: OpcUaConfig.Endpoint) {
        connectionSemaphore.withPermit {
            try {
                logger.debug("Connecting to endpoint: ${endpoint.url}")

                val client = createClient(endpoint)
                val connection = ClientConnection(client, endpoint)
                clients[endpoint.url] = connection

                client.connect().get()
                connection.isConnected = true
                connectedCount.incrementAndGet()

                logger.info("Connected to ${endpoint.url} (${connectedCount.get()}/${totalEndpoints.get()})")

                // Setup subscription or polling for this endpoint
                setupDataCollection(connection)

            } catch (e: Exception) {
                logger.error("Failed to connect to ${endpoint.url}: ${e.message}")
                scheduleReconnect(endpoint.url)
            }
        }
    }

    private suspend fun createClient(endpoint: OpcUaConfig.Endpoint): OpcUaClient = withContext(Dispatchers.IO) {
        val endpoints = try {
            DiscoveryClient.getEndpoints(endpoint.url).get()
        } catch (e: Exception) {
            logger.warn("Discovery failed for ${endpoint.url}, creating fallback endpoint")
            createFallbackEndpoint(endpoint.url)
        }

        val rewrittenEndpoints = endpoints.map { original ->
            EndpointDescription(
                endpoint.url,
                original.server,
                original.serverCertificate,
                original.securityMode,
                original.securityPolicyUri,
                original.userIdentityTokens,
                original.transportProfileUri,
                original.securityLevel
            )
        }

        val selectedEndpoint = rewrittenEndpoints.find {
            it.securityMode == MessageSecurityMode.None
        } ?: endpoints.firstOrNull() ?: throw IllegalStateException("No suitable endpoint found for ${endpoint.url}")

        val config = OpcUaClientConfigBuilder()
            .setApplicationName(LocalizedText(opcUaConfig.applicationName))
            .setApplicationUri("urn:spring-boot:opcua:multi-client")
            .setEndpoint(selectedEndpoint)
            .build()

        OpcUaClient.create(config)
    }

    private fun createFallbackEndpoint(url: String): List<EndpointDescription> {
        return listOf(
            EndpointDescription(
                url,
                ApplicationDescription(
                    "urn:fallback:opcua:server",
                    "urn:fallback:opcua:server:product",
                    LocalizedText("en", "Fallback OPC UA Server"),
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

    private suspend fun setupDataCollection(connection: ClientConnection) {
        try {
            if (!tryCreateSubscription(connection)) {
                logger.warn("Subscription failed for ${connection.endpoint.url}, falling back to polling")
                startPolling(connection)
            }
        } catch (e: Exception) {
            logger.error("Failed to setup data collection for ${connection.endpoint.url}", e)
        }
    }

    private suspend fun tryCreateSubscription(connection: ClientConnection): Boolean = withContext(Dispatchers.IO) {
        try {
            val subscription = connection.client.subscriptionManager
                .createSubscription(opcUaConfig.subscriptionInterval.toDouble())
                .get()

            val nodeIdStrings = buildAllNodeIds(connection.endpoint)

            if (nodeIdStrings.isEmpty()) {
                logger.warn("No node IDs configured for endpoint ${connection.endpoint.url}")
                return@withContext false
            }

            val monitoredItems = nodeIdStrings.map { nodeIdString ->
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
                        handleDataChange(connection.endpoint.url, item, value)
                    }
                    successCount++
                } else {
                    logger.debug("Failed monitored item for {}: {}", connection.endpoint.url, item.readValueId.nodeId)
                }
            }

            logger.debug("Created $successCount/${monitoredItems.size} monitored items for ${connection.endpoint.url}")
            true
        } catch (e: Exception) {
            logger.error("Subscription creation failed for ${connection.endpoint.url}: ${e.message}")
            false
        }
    }

    private fun startPolling(connection: ClientConnection) {
        scope.launch {
            while (connection.isConnected) {
                try {
                    pollNodes(connection)
                    delay(opcUaConfig.subscriptionInterval)
                } catch (e: Exception) {
                    logger.error("Polling error for ${connection.endpoint.url}", e)
                    delay(5000)
                }
            }
        }
    }

    private suspend fun pollNodes(connection: ClientConnection) = withContext(Dispatchers.IO) {
        try {
            val nodeIdStrings = buildAllNodeIds(connection.endpoint)

            if (nodeIdStrings.isEmpty()) {
                logger.warn("No node IDs configured for polling endpoint ${connection.endpoint.url}")
                return@withContext
            }

            val nodeIds = nodeIdStrings.map { NodeId.parse(it) }

            val readValueIds = nodeIds.map { nodeId ->
                ReadValueId(nodeId, AttributeId.Value.uid(), null, QualifiedName.NULL_VALUE)
            }

            val dataValues = connection.client.read(0.0, TimestampsToReturn.Both, readValueIds).get().results

            dataValues.forEachIndexed { index, dataValue ->
                if (dataValue.statusCode?.isGood == true) {
                    val nodeId = nodeIds[index].toParseableString()
                    handlePolledData(connection.endpoint.url, nodeId, dataValue)
                }
            }

        } catch (e: Exception) {
            logger.error("Polling failed for ${connection.endpoint.url}", e)
            throw e
        }
    }

    private fun buildAllNodeIds(endpoint: OpcUaConfig.Endpoint): List<String> {
        val nodeIds = mutableListOf<String>()

        // 1. Controller-level nodes
        nodeIdsConfig.guisController.forEach { controllerNode ->
            nodeIds.add("ns=2;s=GIUSController.$controllerNode")
        }

        // 2. & 3. & 4. Meter and subsystem nodes
        endpoint.meters.forEach { meter ->
            // Meter-level nodes
            nodeIdsConfig.meter.forEach { meterNode ->
                nodeIds.add("ns=2;s=GIUSController.${meter.guid}.$meterNode")
            }

            // Subsystem nodes
            meter.subs.forEach { sub ->
                nodeIdsConfig.sub.forEach { subNode ->
                    nodeIds.add("ns=2;s=GIUSController.${meter.guid}.$sub.$subNode")
                }
            }
        }

        return nodeIds
    }

    private fun handleDataChange(endpointUrl: String, item: UaMonitoredItem, value: DataValue) {
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

    private fun handlePolledData(endpointUrl: String, nodeId: String, value: DataValue) {
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

    private fun createRawMeterData(endpointUrl: String, nodeId: String, value: DataValue): RawMeterData {
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
            timestamp = LocalDateTime.now(),
            serverTimestamp = value.serverTime?.javaInstant?.atZone(ZoneId.of("Europe/Moscow"))?.toLocalDateTime()
        )
    }

    private fun scheduleReconnect(endpointUrl: String) {
        val connection = clients[endpointUrl] ?: return

        // Cancel existing reconnect job
        connection.reconnectJob?.cancel()

        connection.reconnectJob = scope.launch {
            val now = System.currentTimeMillis()
            val timeSinceLastAttempt = now - connection.lastReconnectAttempt
            val minReconnectInterval = 30000L // 30 seconds

            if (timeSinceLastAttempt < minReconnectInterval) {
                delay(minReconnectInterval - timeSinceLastAttempt)
            }

            connection.lastReconnectAttempt = System.currentTimeMillis()
            logger.info("Attempting reconnect to $endpointUrl")

            try {
                // Clean up old client
                connection.client.disconnect()
                connectedCount.decrementAndGet()

                // Create new connection
                connectToEndpoint(connection.endpoint)
            } catch (e: Exception) {
                logger.error("Reconnect failed for $endpointUrl", e)
                // Schedule another reconnect with exponential backoff
                delay(60000) // Wait 1 minute before next attempt
                scheduleReconnect(endpointUrl)
            }
        }
    }

    private fun startConnectionMonitor() {
        scope.launch {
            while (true) {
                delay(60000) // Check every minute

                val connected = connectedCount.get()
                val total = totalEndpoints.get()

                logger.info("Connection status: $connected/$total endpoints connected")

                // Check for stuck connections and trigger reconnects if needed
                clients.values.forEach { connection ->
                    if (connection.isConnected) {
                        scope.launch {
                            try {
                                // Simple health check - try to read server status
                                val testResult = connection.client.namespaceTable
                                // If we get here, connection is healthy
                            } catch (e: Exception) {
                                logger.warn("Health check failed for ${connection.endpoint.url}, triggering reconnect")
                                connection.isConnected = false
                                scheduleReconnect(connection.endpoint.url)
                            }
                        }
                    }
                }
            }
        }
    }

    // Public API methods
    fun getDataStream(): Flow<RawMeterData> = dataChannel.receiveAsFlow()

    fun getConnectionStats(): Map<String, Any> {
        return mapOf(
            "connected" to connectedCount.get(),
            "total" to totalEndpoints.get(),
            "connectionRate" to (connectedCount.get().toDouble() / totalEndpoints.get() * 100).let {
                "%.1f%%".format(it)
            }
        )
    }

    fun getEndpointStatus(): Map<String, Boolean> {
        return clients.mapValues { it.value.isConnected }
    }

    suspend fun testConnection(endpointUrl: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val connection = clients[endpointUrl] ?: return@withContext false
            if (!connection.isConnected) return@withContext false

            connection.client.namespaceTable
            true
        } catch (e: Exception) {
            logger.debug("Connection test failed for $endpointUrl", e)
            false
        }
    }

    @PreDestroy
    fun cleanup() {
        logger.info("Shutting down OPC UA multi-client service")

        scope.cancel()

        clients.values.forEach { connection ->
            try {
                connection.isConnected = false
                connection.reconnectJob?.cancel()
                connection.client.disconnect().get()
            } catch (e: Exception) {
                logger.warn("Error disconnecting from ${connection.endpoint.url}", e)
            }
        }

        clients.clear()
        logger.info("OPC UA multi-client service shutdown complete")
    }
}