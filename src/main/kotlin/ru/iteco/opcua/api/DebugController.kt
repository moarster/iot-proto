package ru.iteco.opcua.api


import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import ru.iteco.opcua.client.OpcUaClientService
import ru.iteco.opcua.config.OpcUaConfig

@RestController
@RequestMapping("/debug")
class DebugController(
    private val opcUaClientService: OpcUaClientService,
    private val opcUaConfig: OpcUaConfig
) {

    @GetMapping("/connection")
    suspend fun checkConnection(): Map<String, Any> {
        return mapOf(
            "connected" to opcUaClientService.isClientConnected(),
            "endpointUrl" to opcUaConfig.endpointUrl,
            "nodeIds" to opcUaConfig.nodeIds,
            "subscriptionInterval" to opcUaConfig.subscriptionInterval
        )
    }

    @GetMapping("/test")
    suspend fun testConnection(): Map<String, Any> {
        val testResult = opcUaClientService.testConnection()
        return mapOf(
            "testPassed" to testResult,
            "timestamp" to System.currentTimeMillis()
        )
    }
}