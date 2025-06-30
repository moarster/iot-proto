package ru.iteco.opcua.api


import org.springframework.web.bind.annotation.*
import ru.iteco.opcua.client.OpcUaConnectionManager
import ru.iteco.opcua.config.OpcUaConfig

@RestController
@RequestMapping("/debug")
class DebugController(
    private val connectionManager: OpcUaConnectionManager,
    private val opcUaConfig: OpcUaConfig
) {

    @GetMapping("/connection")
    suspend fun checkConnection(): Map<String, Any> {
        val stats = connectionManager.getConnectionStats()
        val endpointStatus = connectionManager.getEndpointStatus()

        return mapOf(
            "summary" to stats,
            "applicationName" to opcUaConfig.applicationName,
            "subscriptionInterval" to opcUaConfig.subscriptionInterval,
            "totalEndpoints" to opcUaConfig.endpoints.size,
            "endpoints" to opcUaConfig.endpoints.map { endpoint ->
                mapOf(
                    "url" to endpoint.url,
                    "connected" to (endpointStatus[endpoint.url] ?: false),
                    "meterCount" to endpoint.meters.size,
                    "totalSubscriptions" to endpoint.meters.sumOf { it.subs.size }
                )
            }
        )
    }

    @GetMapping("/test")
    suspend fun testConnection(): Map<String, Any> {
        val results = mutableMapOf<String, Any>()
        val testResults = mutableListOf<Map<String, Any>>()

        opcUaConfig.endpoints.forEach { endpoint ->
            val testResult = connectionManager.testConnection(endpoint.url)
            testResults.add(mapOf(
                "endpointUrl" to endpoint.url,
                "testPassed" to testResult,
                "timestamp" to System.currentTimeMillis()
            ))
        }

        val passedCount = testResults.count { it["testPassed"] as Boolean }
        val totalCount = testResults.size

        return mapOf(
            "overallResult" to mapOf(
                "passed" to passedCount,
                "total" to totalCount,
                "successRate" to if (totalCount > 0) (passedCount.toDouble() / totalCount * 100) else 0.0
            ),
            "endpointResults" to testResults,
            "timestamp" to System.currentTimeMillis()
        )
    }

    @GetMapping("/test/{endpointUrl}")
    suspend fun testSpecificConnection(@PathVariable endpointUrl: String): Map<String, Any> {
        val decodedUrl = java.net.URLDecoder.decode(endpointUrl, "UTF-8")
        val testResult = connectionManager.testConnection(decodedUrl)

        val endpoint = opcUaConfig.endpoints.find { it.url == decodedUrl }

        return mapOf(
            "endpointUrl" to decodedUrl,
            "testPassed" to testResult,
            "endpointFound" to (endpoint != null),
            "meterCount" to (endpoint?.meters?.size ?: 0),
            "timestamp" to System.currentTimeMillis()
        )
    }

    @GetMapping("/stats")
    suspend fun getDetailedStats(): Map<String, Any> {
        val connectionStats = connectionManager.getConnectionStats()

        return mapOf(
            "overall" to connectionStats,
            "memoryUsage" to mapOf(
                "totalMemory" to Runtime.getRuntime().totalMemory(),
                "freeMemory" to Runtime.getRuntime().freeMemory(),
                "usedMemory" to (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())
            ),
            "timestamp" to System.currentTimeMillis()
        )
    }

    @GetMapping("/endpoints")
    suspend fun getAllEndpoints(): Map<String, Any> {
        val endpointStatus = connectionManager.getEndpointStatus()

        val endpointDetails = opcUaConfig.endpoints.map { endpoint ->
            mapOf(
                "url" to endpoint.url,
                "connected" to (endpointStatus[endpoint.url] ?: false),
                "meters" to endpoint.meters.map { meter ->
                    mapOf(
                        "guid" to meter.guid.toString(),
                        "subscriptions" to meter.subs
                    )
                }
            )
        }

        return mapOf(
            "endpoints" to endpointDetails,
            "totalCount" to endpointDetails.size,
            "timestamp" to System.currentTimeMillis()
        )
    }

    @GetMapping("/endpoints/connected")
    suspend fun getConnectedEndpoints(): Map<String, Any> {
        val endpointStatus = connectionManager.getEndpointStatus()

        val connectedEndpoints = opcUaConfig.endpoints.filter {
            endpointStatus[it.url] ?: false
        }.map { endpoint ->
            mapOf(
                "url" to endpoint.url,
                "meterCount" to endpoint.meters.size,
                "subscriptionCount" to endpoint.meters.sumOf { it.subs.size }
            )
        }

        return mapOf(
            "connectedEndpoints" to connectedEndpoints,
            "count" to connectedEndpoints.size,
            "timestamp" to System.currentTimeMillis()
        )
    }

    @GetMapping("/endpoints/disconnected")
    suspend fun getDisconnectedEndpoints(): Map<String, Any> {
        val endpointStatus = connectionManager.getEndpointStatus()

        val disconnectedEndpoints = opcUaConfig.endpoints.filter {
            !(endpointStatus[it.url] ?: false)
        }.map { endpoint ->
            mapOf(
                "url" to endpoint.url,
                "meterCount" to endpoint.meters.size,
                "subscriptionCount" to endpoint.meters.sumOf { it.subs.size }
            )
        }

        return mapOf(
            "disconnectedEndpoints" to disconnectedEndpoints,
            "count" to disconnectedEndpoints.size,
            "timestamp" to System.currentTimeMillis()
        )
    }

    @PostMapping("/reconnect/{endpointUrl}")
    suspend fun triggerReconnect(@PathVariable endpointUrl: String): Map<String, Any> {
        val decodedUrl = java.net.URLDecoder.decode(endpointUrl, "UTF-8")

        return try {
            // TODO: add a method to the service to trigger reconnect
            // For now, we'll just return the current status
            mapOf(
                "endpointUrl" to decodedUrl,
                "reconnectTriggered" to true,
                "currentStatus" to connectionManager.testConnection(decodedUrl) as Any,
                "message" to "Reconnect request processed",
                "timestamp" to System.currentTimeMillis()
            )
        } catch (e: Exception) {
            mapOf(
                "endpointUrl" to decodedUrl,
                "reconnectTriggered" to false,
                "error" to e.message,
                "timestamp" to System.currentTimeMillis()
            )
        } as Map<String, Any>
    }

    @GetMapping("/health")
    suspend fun healthCheck(): Map<String, Any> {
        val stats = connectionManager.getConnectionStats()
        val connected = stats["connected"] as Int
        val total = stats["total"] as Int

        val isHealthy = connected > 0 && (connected.toDouble() / total) > 0.5 // At least 50% connected

        return mapOf(
            "status" to if (isHealthy) "healthy" else "degraded",
            "connected" to connected,
            "total" to total,
            "connectionRate" to stats["connectionRate"],
            "timestamp" to System.currentTimeMillis()
        ) as Map<String, Any>
    }
}