package ru.iteco.opcua.service

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.reactive.awaitFirst
import kotlinx.coroutines.reactive.awaitFirstOrNull
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.ReactiveRedisTemplate
import org.springframework.stereotype.Service
import ru.iteco.opcua.model.NodeMetadata
import ru.iteco.opcua.model.RawMeterData
import java.time.Duration

@Service
class MetadataService(
    private val reactiveRedisTemplate: ReactiveRedisTemplate<String, String>,
    private val objectMapper: ObjectMapper
) {
    private val logger = LoggerFactory.getLogger(MetadataService::class.java)

    // Key prefix for metadata in Redis
    private val _nodePrefix = "uspd:"
    private val _ttlDays = 30L // Time-to-live for metadata in Redis

    /**
     * Saves metadata extracted from RawMeterData into Redis.
     * Metadata is identified by its nodeId.
     *
     * @param rawData The raw meter data containing metadata.
     */
    suspend fun saveMetadata(rawData: RawMeterData) {
        if (rawData.isMeasurement) {
            logger.warn("Attempted to save measurement node as metadata. NodeId: ${rawData.nodeId}")
            return
        }

        val metadata = NodeMetadata(
            value = rawData.value,
            dataType = rawData.dataType,
            timestamp = rawData.timestamp,
            serverTimestamp = rawData.serverTimestamp
        )

        val key = _nodePrefix + rawData.endpointUrl + ";" + rawData.nodeId
        try {
            val metadataJson = objectMapper.writeValueAsString(metadata)
            reactiveRedisTemplate.opsForValue().set(key, metadataJson, Duration.ofDays(_ttlDays)).awaitFirst()
            logger.debug("Cached metadata for NodeId: {}", rawData.nodeId)
        } catch (e: Exception) {
            logger.error("Failed to cache metadata for NodeId: ${rawData.nodeId}", e)
        }
    }

    /**
     * Retrieves metadata for a given NodeId from Redis.
     *
     * @param metaDataKey The NodeId for which to retrieve metadata.
     * @return NodeMetadata object if found, null otherwise.
     */
    suspend fun getMetadata(metaDataKey: String): NodeMetadata? {
        val key = _nodePrefix + metaDataKey
        return try {
            val metadataJson = reactiveRedisTemplate.opsForValue().get(key).awaitFirstOrNull()
            metadataJson?.let {
                objectMapper.readValue(it, NodeMetadata::class.java)
            }
        } catch (e: Exception) {
            logger.error("Failed to retrieve metadata for NodeId: $metaDataKey", e)
            null
        }
    }
/*
    *//**
     * Retrieves all cached metadata for a specific meter.
     * This function might require iterating through keys or using a more
     * sophisticated Redis data structure if you have a very large number of meters.
     * For now, it assumes meter-specific metadata can be identified by a pattern.
     *
     * @param meterGuid The GUID of the meter.
     * @return A map of NodeId to NodeMetadata for the given meter.
     *//*
    suspend fun getMetadataForMeter(meterGuid: String): Map<String, NodeMetadata> {
        val meterMetadata = mutableMapOf<String, NodeMetadata>()
        // This is a simplistic approach and might be inefficient for many keys.
        // Consider Redis hashes or sets for more structured metadata per meter if performance is an issue.
        try {
            reactiveRedisTemplate.keys("${_nodePrefix}*")
                .collect { key ->
                    val nodeId = key.removePrefix(_nodePrefix)
                    val metadata = getMetadata(nodeId)
                    if (metadata != null && metadata.meterGuid == meterGuid) {
                        meterMetadata[nodeId] = metadata
                    }
                }
        } catch (e: Exception) {
            logger.error("Failed to retrieve metadata for meter $meterGuid", e)
        }
        return meterMetadata
    }

    *//**
     * Retrieves all cached metadata for a specific controller.
     * Similar considerations as `getMetadataForMeter`.
     *
     * @param controllerId The ID of the controller.
     * @return A map of NodeId to NodeMetadata for the given controller.
     *//*
    suspend fun getMetadataForController(controllerId: String): Map<String, NodeMetadata> {
        val controllerMetadata = mutableMapOf<String, NodeMetadata>()
        try {
            reactiveRedisTemplate.keys("${_nodePrefix}*")
                .collect { key ->
                    val nodeId = key.removePrefix(_nodePrefix)
                    val metadata = getMetadata(nodeId)
                    if (metadata != null && metadata.controllerId == controllerId) {
                        controllerMetadata[nodeId] = metadata
                    }
                }
        } catch (e: Exception) {
            logger.error("Failed to retrieve metadata for controller $controllerId", e)
        }
        return controllerMetadata
    }*/
}