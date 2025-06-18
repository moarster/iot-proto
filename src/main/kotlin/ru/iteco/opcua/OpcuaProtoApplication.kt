package ru.iteco.opcua

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
import org.springframework.data.mongodb.repository.config.EnableReactiveMongoRepositories
import org.springframework.data.r2dbc.repository.config.EnableR2dbcRepositories
import ru.iteco.opcua.config.OpcUaConfig

@SpringBootApplication
@EnableReactiveMongoRepositories
@EnableR2dbcRepositories
@EnableConfigurationProperties(OpcUaConfig::class)
class OpcuaProtoApplication

fun main(args: Array<String>) {
    runApplication<OpcuaProtoApplication>(*args)
}
