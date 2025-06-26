package ru.iteco.opcua.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import java.util.*

@Component
@ConfigurationProperties(prefix = "opcua")
class OpcUaConfig {
    var applicationName: String = "Spring Boot OPC UA Client"
    var subscriptionInterval: Long = 1000
    var endpoints: List<Endpoint> = emptyList()

    class Endpoint {
        lateinit var url: String
        lateinit var type: String
        var meters: List<Meter> = emptyList()
    }

    class Meter {
        lateinit var guid: UUID
        var subs: List<Int> = emptyList()
    }

}