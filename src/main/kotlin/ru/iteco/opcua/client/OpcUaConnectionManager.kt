package ru.iteco.opcua.client

import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.eclipse.milo.opcua.sdk.client.OpcUaClient
import org.eclipse.milo.opcua.sdk.client.api.config.OpcUaClientConfigBuilder
import org.eclipse.milo.opcua.stack.client.DiscoveryClient
import org.eclipse.milo.opcua.stack.core.types.builtin.LocalizedText
import org.eclipse.milo.opcua.stack.core.types.enumerated.ApplicationType
import org.eclipse.milo.opcua.stack.core.types.enumerated.MessageSecurityMode
import org.eclipse.milo.opcua.stack.core.types.structured.ApplicationDescription
import org.eclipse.milo.opcua.stack.core.types.structured.EndpointDescription
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import ru.iteco.opcua.config.Endpoint
import ru.iteco.opcua.config.OpcUaConfig
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UByte
import kotlin.text.get

@Component
class OpcUaConnectionManager(
    private val opcUaConfig: OpcUaConfig
) {
    private val logger = LoggerFactory.getLogger(OpcUaConnectionManager::class.java)

    private val clients = ConcurrentHashMap<String, ClientConnection>()
    private val maxConcurrentConnections = 50
    private val connectionSemaphore = Semaphore(maxConcurrentConnections)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Connection stats
    private val connectedCount = AtomicInteger(0)
    private val totalEndpoints = AtomicInteger(0)

    data class ClientConnection(
        val client: OpcUaClient,
        val endpoint: Endpoint,
        @Volatile var isConnected: Boolean = false,
        @Volatile var lastReconnectAttempt: Long = 0,
        var reconnectJob: Job? = null
    )

    suspend fun initializeConnections() {
        logger.info("Инициализация OPC UA мульти-клиента, количество подключений: ${opcUaConfig.endpoints.size}")
        totalEndpoints.set(opcUaConfig.endpoints.size)

        if (opcUaConfig.endpoints.isEmpty()) {
            logger.warn("Не настроено ни одного OPC UA сервера для подключения")
            return
        }

        // Connect in batches to avoid overwhelming the network
        opcUaConfig.endpoints.chunked(10).forEach { batch ->
            batch.forEach { endpoint ->
                scope.launch { connectToEndpoint(endpoint) }
            }
            delay(1000) // Pause between batches
        }

        startConnectionMonitor()
    }

    private suspend fun connectToEndpoint(endpoint: Endpoint) {
        connectionSemaphore.withPermit {
            try {
                logger.debug("Подключение к серверу {}", endpoint)

                val client = createClient(endpoint.url)
                val connection = ClientConnection(client, endpoint)
                clients[endpoint.url] = connection

                client.connect().get()
                connection.isConnected = true
                connectedCount.incrementAndGet()

                logger.info("Подключено к $endpoint (${connectedCount.get()} из ${totalEndpoints.get()})")

            } catch (e: Exception) {
                logger.error("Ошибка подключения к ${endpoint}: ${e.message}")
                scheduleReconnect(endpoint.url)
            }
        }
    }

    private suspend fun createClient(serverEndpoint: String): OpcUaClient = withContext(Dispatchers.IO) {
        val dataEndpoints = try {
            DiscoveryClient.getEndpoints(serverEndpoint).get()
        } catch (e: Exception) {
            logger.warn("Сервис обнаружения недоступен для ${serverEndpoint}, используем резервный конструктор")
            createFallbackEndpoint(serverEndpoint)
        }

        // Rewrite endpoint URLs to use IP instead of hostname
        val rewrittenEndpoints = dataEndpoints.map { original ->
            EndpointDescription(
                serverEndpoint,
                original.server,
                original.serverCertificate,
                original.securityMode,
                original.securityPolicyUri,
                original.userIdentityTokens,
                original.transportProfileUri,
                original.securityLevel
            )
        }

        // Select endpoint without security
        val selectedEndpoint = rewrittenEndpoints.find {
            it.securityMode == MessageSecurityMode.None
        } ?: rewrittenEndpoints.firstOrNull()
        ?: throw IllegalStateException("No suitable endpoint found for $serverEndpoint")

        val config = OpcUaClientConfigBuilder()
            .setApplicationName(LocalizedText(opcUaConfig.applicationName))
            .setApplicationUri("urn:spring-boot:opcua:multi-client")
            .setEndpoint(selectedEndpoint)
            .build()

        OpcUaClient.create(config)
    }

    // TODO: возможно не понадобится
    // да и этот вариант кажется не канает
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
                UByte.MIN
            )
        )
    }

    private fun scheduleReconnect(endpointUrl: String) {
        val connection = clients[endpointUrl] ?: return

        connection.reconnectJob?.cancel()
        connection.reconnectJob = scope.launch {
            val now = System.currentTimeMillis()
            val timeSinceLastAttempt = now - connection.lastReconnectAttempt
            val minReconnectInterval = 30000L

            if (timeSinceLastAttempt < minReconnectInterval) {
                delay(minReconnectInterval - timeSinceLastAttempt)
            }

            connection.lastReconnectAttempt = System.currentTimeMillis()
            logger.info("Attempting reconnect to $endpointUrl")

            try {
                connection.client.disconnect()
                connectedCount.decrementAndGet()
                connectToEndpoint(connection.endpoint)
            } catch (e: Exception) {
                logger.error("Reconnect failed for $endpointUrl", e)
                delay(60000)
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

                // Health check for connections
                clients.values.forEach { connection ->
                    if (connection.isConnected) {
                        scope.launch {
                            try {
                                connection.client.namespaceTable // Simple health check
                            } catch (e: Exception) {
                                logger.warn("Health check failed for ${connection.endpoint.url}")
                                connection.isConnected = false
                                scheduleReconnect(connection.endpoint.url)
                            }
                        }
                    }
                }
            }
        }
    }

    fun getConnection(endpointUrl: String): ClientConnection? = clients[endpointUrl]

    fun getConnectedClients(): List<ClientConnection> =
        clients.values.filter { it.isConnected }

    fun getConnectionStats(): Map<String, Any> = mapOf(
        "connected" to connectedCount.get(),
        "total" to totalEndpoints.get(),
        "connectionRate" to "%.1f%%".format(
            connectedCount.get().toDouble() / totalEndpoints.get() * 100
        )
    )

    suspend fun testConnection(endpointUrl: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val connection = clients[endpointUrl] ?: return@withContext false
            if (!connection.isConnected) return@withContext false
            connection.client.namespaceTable
            true
        } catch (e: Exception) {
            false
        }
    }

    fun getEndpointStatus(): Map<String, Boolean> {
        return clients.mapValues { it.value.isConnected }
    }


    @PreDestroy
    fun cleanup() {
        logger.info("Shutting down OPC UA connection manager")
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
        logger.info("OPC UA connection manager shutdown complete")
    }
}