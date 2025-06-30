package ru.iteco.opcua.metadata

import ru.iteco.opcua.model.MeterType

data class Metadata (
    var uspdId: String?=null,
    var resourceType: MeterType?=null,
){


    companion object {
        var basePrefix = "ns=2;s=GIUSController."
        fun getMetadataNodesFor(node: NodeClassification): Map<String, String> {
            var metadataNodes = mapOf("uspdId" to "$basePrefix.GUID")
            if (node.subsystem != null) {
                metadataNodes =
                    metadataNodes.plus("resourceType" to "$basePrefix.${node.meterId}.${node.subsystem}.ResType")
            }
            return metadataNodes
        }
    }
}