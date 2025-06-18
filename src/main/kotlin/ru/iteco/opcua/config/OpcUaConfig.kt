package ru.iteco.opcua.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Configuration

@Configuration
@ConfigurationProperties(prefix = "opcua")
data class OpcUaConfig (
    var endpointUrl: String = "opc.tcp://localhost:4840",
    var username: String? = null,
    var password: String? = null,
    var applicationName: String = "Spring Boot OPC UA Client",
    var subscriptionInterval: Long = 1000,
    var nodeIds: List<String> = listOf(
        "ns=2;s=HotWaterMeter.Flow",
        "ns=2;s=HotWaterMeter.Temperature",
        "ns=2;s=ColdWaterMeter.Flow",
        "ns=2;s=ColdWaterMeter.Temperature",
        "ns=2;s=HeatMeter.Energy",
        "ns=2;s=HeatMeter.Power"
    )
)