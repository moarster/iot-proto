package ru.iteco.opcua.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.milo.opcua.stack.core.AttributeId
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId
import org.eclipse.milo.opcua.stack.core.types.builtin.QualifiedName
import org.eclipse.milo.opcua.stack.core.types.enumerated.TimestampsToReturn
import org.eclipse.milo.opcua.stack.core.types.structured.ReadValueId
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import ru.iteco.opcua.client.OpcUaConnectionManager
import ru.iteco.opcua.model.NodeMetadata
import ru.iteco.opcua.model.RawMeterData
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

@Service
class MetadataResolver(
    private val metadataService: MetadataService,
    private val connectionManager: OpcUaConnectionManager
) {
    private val logger = LoggerFactory.getLogger(MetadataResolver::class.java)

    // Cache for metadata that's currently being fetched to avoid duplicate requests
    private val pendingMetadataRequests = ConcurrentHashMap<String, CompletableDeferred<NodeMetadata?>>()

    /**
     * Resolves metadata for a node. First checks Redis cache, then polls OPC server if needed.
     */
    suspend fun resolveMetadata(
        endpointUrl: String,
        nodeId: String,
        metadataType: MetadataType
    ): NodeMetadata? {
        val cacheKey = "opcua:$endpointUrl;$nodeId"

        // Check cache first
        metadataService.getMetadata(cacheKey)?.let { return it }

        // If not in cache, fetch from server
        return fetchMetadataFromServer(endpointUrl, nodeId, metadataType)
    }

    /**
     * Batch resolve metadata for multiple nodes to improve efficiency
     */
    suspend fun batchResolveMetadata(
        endpointUrl: String,
        metadataRequests: List<MetadataRequest>
    ): Map<String, NodeMetadata?> {
        val results = mutableMapOf<String, NodeMetadata?>()
        val needsFetching = mutableListOf<MetadataRequest>()

        // Check cache for all requests first
        metadataRequests.forEach { request ->
            val cacheKey = "opcua:$endpointUrl;${request.nodeId}"
            val cached = metadataService.getMetadata(cacheKey)
            if (cached != null) {
                results[request.nodeId] = cached
            } else {
                needsFetching.add(request)
            }
        }

        // Batch fetch missing metadata
        if (needsFetching.isNotEmpty()) {
            val fetched = batchFetchFromServer(endpointUrl, needsFetching)
            results.putAll(fetched)
        }

        return results
    }

    private suspend fun fetchMetadataFromServer(
        endpointUrl: String,
        nodeId: String,
        metadataType: MetadataType
    ): NodeMetadata? {
        val requestKey = "$endpointUrl:$nodeId"

        // Check if we're already fetching this metadata
        val existingRequest = pendingMetadataRequests[requestKey]
        if (existingRequest != null) {
            return existingRequest.await()
        }

        val deferred = CompletableDeferred<NodeMetadata?>()
        pendingMetadataRequests[requestKey] = deferred

        try {
            val connection = connectionManager.getConnection(endpointUrl)
            if (connection == null || !connection.isConnected) {
                logger.warn("No connection available for $endpointUrl")
                deferred.complete(null)
                return null
            }

            val result = pollSingleNode(connection, nodeId, metadataType)
            if (result != null) {
                // Cache the result
                val cacheKey = "opcua:$endpointUrl;$nodeId"
                metadataService.saveMetadata(createRawMeterDataForCaching(
                    endpointUrl, nodeId, result, metadataType
                ))
            }

            deferred.complete(result)
            return result

        } catch (e: Exception) {
            logger.error("Failed to fetch metadata for $nodeId from $endpointUrl", e)
            deferred.complete(null)
            return null
        } finally {
            pendingMetadataRequests.remove(requestKey)
        }
    }

    private suspend fun batchFetchFromServer(
        endpointUrl: String,
        requests: List<MetadataRequest>
    ): Map<String, NodeMetadata?> = withContext(Dispatchers.IO) {
        val connection = connectionManager.getConnection(endpointUrl)
        if (connection == null || !connection.isConnected) {
            logger.warn("No connection available for batch fetch from $endpointUrl")
            return@withContext requests.associate { it.nodeId to null }
        }

        try {
            val nodeIds = requests.map { NodeId.parse(it.nodeId) }
            val readValueIds = nodeIds.map { nodeId ->
                ReadValueId(nodeId, AttributeId.Value.uid(), null, QualifiedName.NULL_VALUE)
            }

            val dataValues = connection.client.read(0.0, TimestampsToReturn.Both, readValueIds).get().results
            val results = mutableMapOf<String, NodeMetadata?>()

            dataValues.forEachIndexed { index, dataValue ->
                val request = requests[index]
                if (dataValue.statusCode?.isGood == true) {
                    val metadata = NodeMetadata(
                        value = dataValue.value?.value ?: "null",
                        dataType = dataValue.value?.dataType?.toString() ?: "unknown",
                        timestamp = LocalDateTime.ofInstant(dataValue.sourceTime?.javaInstant, ZoneId.of("Europe/Moscow")),
                        serverTimestamp = LocalDateTime.ofInstant(dataValue.serverTime?.javaInstant, ZoneId.of("Europe/Moscow"))
                    )

                    // Cache the result
                    val cacheKey = "opcua:$endpointUrl;${request.nodeId}"
                    metadataService.saveMetadata(createRawMeterDataForCaching(
                        endpointUrl, request.nodeId, metadata, request.metadataType
                    ))

                    results[request.nodeId] = metadata
                } else {
                    logger.debug("Failed to read metadata node ${request.nodeId}: ${dataValue.statusCode}")
                    results[request.nodeId] = null
                }
            }

            results
        } catch (e: Exception) {
            logger.error("Batch metadata fetch failed for $endpointUrl", e)
            requests.associate { it.nodeId to null }
        }
    }

    private suspend fun pollSingleNode(
        connection: OpcUaConnectionManager.ClientConnection,
        nodeId: String,
        metadataType: MetadataType
    ): NodeMetadata? = withContext(Dispatchers.IO) {
        try {
            val readValueId = ReadValueId(
                NodeId.parse(nodeId),
                AttributeId.Value.uid(),
                null,
                QualifiedName.NULL_VALUE
            )

            val dataValues = connection.client.read(0.0, TimestampsToReturn.Both, listOf(readValueId)).get().results
            val dataValue = dataValues.firstOrNull()

            if (dataValue?.statusCode?.isGood == true) {
                NodeMetadata(
                    value = dataValue.value?.value ?: "null",
                    dataType = dataValue.value?.dataType?.toString() ?: "unknown",
                    timestamp = LocalDateTime.ofInstant(dataValue.sourceTime?.javaInstant, ZoneId.of("Europe/Moscow")),
                    serverTimestamp = LocalDateTime.ofInstant(dataValue.serverTime?.javaInstant, ZoneId.of("Europe/Moscow"))
                )
            } else {
                logger.debug("Failed to read node $nodeId: ${dataValue?.statusCode}")
                null
            }
        } catch (e: Exception) {
            logger.error("Failed to poll node $nodeId", e)
            null
        }
    }

    private fun createRawMeterDataForCaching(
        endpointUrl: String,
        nodeId: String,
        metadata: NodeMetadata,
        metadataType: MetadataType
    ): RawMeterData {
        return RawMeterData(
            nodeId = nodeId,
            endpointUrl = endpointUrl,
            value = metadata.value,
            dataType = metadata.dataType,
            quality = "good",
            timestamp = metadata.timestamp,
            serverTimestamp = metadata.serverTimestamp,
            uspdId = "unknown", // Will be resolved later
            meterId = "unknown",
            subsystem = 0,
            resourceType = metadataType.name.lowercase(),
            isMeasurement = false, // This is metadata, not measurement
            unit = "none",
            periodStart = null,
            periodEnd = null
        )
    }

    data class MetadataRequest(
        val nodeId: String,
        val metadataType: MetadataType
    )

    enum class MetadataType {
        USPD_GUID,
        METER_MODEL,
        METER_SERIAL,
        SUB_ADDRESS,
        OTHER
    }
}