package ru.iteco.opcua.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.KotlinModule
import jakarta.annotation.PostConstruct
import org.springframework.context.annotation.Configuration

@Configuration
class NodeIdsConfig {

    val guisController: MutableList<String> = mutableListOf()
    val meter: MutableList<String> = mutableListOf()
    val sub: MutableList<String> = mutableListOf()
    val kotlinModule = KotlinModule.Builder().build()

    @PostConstruct
    fun init() {
        val mapper = ObjectMapper(YAMLFactory()).registerModule(kotlinModule)
        val resource = javaClass.getResourceAsStream("/nodes.yml")
        val root = mapper.readTree(resource)

        root["properties"].first().get("properties")?.fieldNames()?.forEachRemaining {
            guisController.add(it)
        }

        root["properties"].first().get("patternProperties").first()
            .get("properties")?.fieldNames()?.forEachRemaining {
                meter.add(it)
            }

         root["properties"].first().get("patternProperties").first()
            .get("patternProperties").first().get("properties").properties().forEach { subHigh ->
                sub.add(subHigh.key)
                if (subHigh.value.get("type").equals("object")) {
                    subHigh.value.get("properties").properties().forEach {
                        sub.add(subHigh.key+"."+it.key)
                    }
                }
            }

    }
}