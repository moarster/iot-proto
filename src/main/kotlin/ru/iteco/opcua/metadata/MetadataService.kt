package ru.iteco.opcua.metadata

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.reactive.awaitFirst
import kotlinx.coroutines.reactive.awaitFirstOrNull
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.ReactiveRedisTemplate
import org.springframework.stereotype.Service
import ru.iteco.opcua.model.Metadata
import ru.iteco.opcua.model.Metadata.Companion.getMetadataNodesFor
import ru.iteco.opcua.model.MeterType.Companion.fromString
import java.time.Duration

@Service
class MetadataService(
    private val reactiveRedisTemplate: ReactiveRedisTemplate<String, String>,
    private val objectMapper: ObjectMapper,
    private val nodeClassifier: NodeClassifier
) {
    private val logger = LoggerFactory.getLogger(MetadataService::class.java)


    /**
     * Кэширует метаданные с учетом стратегии
     */
    suspend fun cacheMetadata(endpointUrl: String, nodeId: String, metadata: NodeMetadata) {
        val classification = nodeClassifier.classifyNode(nodeId)
        val key = buildCacheKey(endpointUrl, nodeId)

        try {
            val metadataJson = objectMapper.writeValueAsString(metadata)
            reactiveRedisTemplate.opsForValue().set(key, metadataJson,
                Duration.ofHours(classification.cacheStrategy?.ttlHours ?: 1000)).awaitFirst()
            logger.debug("Метаданные {} кэшированы", nodeId)
        } catch (e: Exception) {
            logger.error("Ошибка кэширования метаданных $nodeId", e)
        }
        // TODO: Добавить теги для группового удаления кэша (по endpoint, по meter)
    }

    /**
     * Batch-получение метаданных из кэша
     */
    suspend fun getBatchMetadata(endpointUrl: String, nodeIds: List<String>): Map<String, NodeMetadata?> {
        if (nodeIds.isEmpty()) {
            return emptyMap()
        }
        val keys = nodeIds.map { nodeId -> buildCacheKey(endpointUrl, nodeId) }

        val values = reactiveRedisTemplate.opsForValue().multiGet(keys).awaitFirstOrNull() ?: emptyList()
        return nodeIds.mapIndexed { index, nodeId ->
            val metadataJson = values.getOrNull(index)
            val metadata = metadataJson?.let {
                objectMapper.readValue(it, NodeMetadata::class.java)
            }
            nodeId to metadata
        }.toMap()
    }

    /**
     * Инвалидация кэша по паттернам
     */
    suspend fun invalidateMetadataCache(endpointUrl: String, meterGuid: String? = null) {
        // TODO: Инвалидировать кэш по endpoint или конкретному счетчику
        // Использовать Redis SCAN с паттернами для поиска ключей
    }

    private fun buildCacheKey(endpointUrl: String, nodeId: String): String {
        return "uspd:$endpointUrl;$nodeId"
    }


    //TODO: Вероятно к удалению
    suspend fun getMeasurementMetadata(endpointUrl: String, node: NodeClassification): Metadata {
        // USPD GUID
        val metadata= Metadata()
        val metadataNodes = getMetadataNodesFor(node)
        val metadataCache = getBatchMetadata(endpointUrl, metadataNodes.values.toList())

        metadata.uspdId = metadataCache[metadataNodes["uspdId"]]?.value.toString()
        metadata.resourceType = fromString(metadataCache[metadataNodes["resourceType"]]?.value.toString())
        return metadata
    }

}