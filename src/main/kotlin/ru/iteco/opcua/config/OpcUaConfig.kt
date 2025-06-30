package ru.iteco.opcua.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import ru.iteco.opcua.model.MeterType
import java.util.*

@Component
@ConfigurationProperties(prefix = "opcua")
class OpcUaConfig {
    var applicationName: String = "Spring Boot OPC UA Client"
    var subscriptionInterval: Long = 1000
    var endpoints: List<Endpoint> = emptyList()
}
class Endpoint {
    lateinit var url: String
    var meters: List<Meter> = emptyList()
    override fun toString(): String = url
}

class Meter {
    lateinit var guid: UUID
    lateinit var type: MeterType
    var subs: List<Int> = emptyList()
}