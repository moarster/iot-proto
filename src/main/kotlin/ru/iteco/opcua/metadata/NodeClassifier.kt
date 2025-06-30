package ru.iteco.opcua.metadata

import org.springframework.stereotype.Service
import ru.iteco.opcua.config.Endpoint
import ru.iteco.opcua.config.NodeIdsConfig

@Service
class NodeClassifier(private val nodeIdsConfig: NodeIdsConfig) {

    fun classifyNode(nodeId: String): NodeClassification {
        val basePrefix = "ns=2;s=GIUSController."
        val nodeIdParts = nodeId.removePrefix(basePrefix).split(".")

        fun matches(part: String, pattern: Regex) = pattern.matches(part)
        val uuidRegex = Regex("^[0-9a-f]$")
        val digitRegex = Regex("^[0-9]$")
        val upperRegex = Regex("^[A-Z]$")

        return when {
            nodeIdParts.size == 1 && matches(nodeIdParts[0], upperRegex) ->
                NodeClassification(
                    isMeasurement = false,
                    nodeId = nodeId,
                    nodeType = NodeClassification.NodeType.CONTROLLER_METADATA,
                    cacheStrategy = MetadataCacheStrategy.CONTROLLER_STATIC
                )

            nodeIdParts.size == 2 &&
                    matches(nodeIdParts[0], uuidRegex) &&
                    matches(nodeIdParts[1], upperRegex) ->
                NodeClassification(
                    isMeasurement = false,
                    meterId = nodeIdParts[0],
                    nodeId = nodeId,
                    nodeType = NodeClassification.NodeType.METER_METADATA_STATIC,
                    cacheStrategy = MetadataCacheStrategy.METER_STATIC
                )

            nodeIdParts.size == 4 &&
                    matches(nodeIdParts[0], uuidRegex) &&
                    matches(nodeIdParts[1], digitRegex) &&
                    nodeIdParts[2] == "Current" ->
                NodeClassification(
                    isMeasurement = true,
                    meterId = nodeIdParts[0],
                    subsystem = nodeIdParts[1],
                    nodeId = nodeId,
                    nodeType = NodeClassification.NodeType.MEASUREMENT_CURRENT
                )

            nodeIdParts.size == 4 &&
                    matches(nodeIdParts[0], uuidRegex) &&
                    matches(nodeIdParts[1], digitRegex) &&
                    nodeIdParts[2] == "History" ->
                NodeClassification(
                    isMeasurement = true,
                    meterId = nodeIdParts[0],
                    subsystem = nodeIdParts[1],
                    nodeId = nodeId,
                    nodeType = NodeClassification.NodeType.MEASUREMENT_HISTORICAL
                )

            nodeIdParts.size == 3 &&
                    matches(nodeIdParts[0], uuidRegex) &&
                    matches(nodeIdParts[1], digitRegex) ->
                NodeClassification(
                    isMeasurement = false,
                    meterId = nodeIdParts[0],
                    subsystem = nodeIdParts[1],
                    nodeId = nodeId,
                    nodeType = NodeClassification.NodeType.SUBSYSTEM_METADATA,
                    cacheStrategy = MetadataCacheStrategy.SUBSYSTEM_STATIC
                )

            else -> NodeClassification(
                isMeasurement = true,
                nodeId = nodeId,
                nodeType = NodeClassification.NodeType.UNCLASSIFIED
            )
        }
    }

    fun buildMetadataNodeIds(uspd: Endpoint): Map<MetadataCacheStrategy, List<String>> {
        // TODO: Группируем узлы метаданных по стратегиям кэширования
        // Это позволит batch-загружать метаданные оптимально
        return mapOf(
            MetadataCacheStrategy.CONTROLLER_STATIC to nodeIdsConfig.uspd.map{"ns=2;s=GIUSController.$it"},
            MetadataCacheStrategy.METER_STATIC to uspd.meters.map { meter ->
                                                                    nodeIdsConfig.meter.map {"ns=2;s=GIUSController.${meter.guid}.$it"}
                                                                }.flatten(),
            MetadataCacheStrategy.SUBSYSTEM_STATIC to uspd.meters.map { meter ->
                meter.subs.map { sub ->
                    nodeIdsConfig.sub.map {"ns=2;s=GIUSController.${meter.guid}.$sub.$it"}
                }
            }.flatten().flatten()
        )


    }

}
