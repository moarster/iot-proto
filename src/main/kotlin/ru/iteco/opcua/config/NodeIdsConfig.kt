package ru.iteco.opcua.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.KotlinModule
import jakarta.annotation.PostConstruct
import org.springframework.context.annotation.Configuration

@Configuration
class NodeIdsConfig {

    val uspd: MutableList<String> = mutableListOf()
    val meter: MutableList<String> = mutableListOf()
    val sub: MutableList<String> = mutableListOf()
    val current: MutableList<String> = mutableListOf()
    val history: MutableList<String> = mutableListOf()
    val kotlinModule = KotlinModule.Builder().build()

    @PostConstruct
    fun init() {
        val mapper = ObjectMapper(YAMLFactory()).registerModule(kotlinModule)
        val resource = javaClass.getResourceAsStream("/node-eds.yml")
        val root = mapper.readTree(resource)

        root["controller"]?.fieldNames()?.forEachRemaining {
            uspd.add(it)
        }
        root["meter"]?.fieldNames()?.forEachRemaining {
            meter.add(it)
        }
        root["sub"]?.fieldNames()?.forEachRemaining {
            sub.add(it)
        }
        root["current"]?.fieldNames()?.forEachRemaining {
            current.add(it)
        }
        root["history"]?.fieldNames()?.forEachRemaining {
            history.add(it)
        }
    }
}