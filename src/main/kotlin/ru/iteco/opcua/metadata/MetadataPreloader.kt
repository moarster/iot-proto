package ru.iteco.opcua.metadata

import org.eclipse.milo.opcua.stack.core.AttributeId
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId
import org.eclipse.milo.opcua.stack.core.types.builtin.QualifiedName
import org.eclipse.milo.opcua.stack.core.types.enumerated.TimestampsToReturn
import org.eclipse.milo.opcua.stack.core.types.structured.ReadValueId
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import ru.iteco.opcua.client.OpcUaConnectionManager
import java.time.LocalDateTime
import java.time.ZoneId

@Service
class MetadataPreloader(
    private val metadataService: MetadataService,
    private val nodeClassifier: NodeClassifier
) {
    private val logger = LoggerFactory.getLogger(MetadataPreloader::class.java)

    /**
     * Предварительная загрузка всех метаданных при подключении к серверу
     */
    suspend fun preloadMetadata(connection: OpcUaConnectionManager.ClientConnection) {
        // TODO: 1. Получить все узлы метаданных для данного endpoint
        // TODO: 2. Проверить, что есть в кэше (batch-операция)
        // TODO: 3. Batch-загрузить недостающие метаданные с сервера
        // TODO: 4. Закэшировать полученные данные согласно стратегиям

        // Определяем какие метаданные нужны для этого endpoint
        val metadataNodes = nodeClassifier.buildMetadataNodeIds(connection.endpoint)

        // Группируем по стратегиям кэширования
        metadataNodes.forEach { (strategy, nodeIds) ->
            // Проверяем что есть в кэше
            val cached = metadataService.getBatchMetadata(connection.endpoint.url, nodeIds)
            val missing = nodeIds - cached.keys

            if (missing.isNotEmpty()) {
                // Загружаем недостающие с сервера BATCH-операцией
                val freshMetadata = batchReadMetadataFromServer(connection, missing)

                // Кэшируем согласно стратегии
                freshMetadata.forEach { (nodeId, metadata) ->
                    metadataService.cacheMetadata(connection.endpoint.url, nodeId, metadata)
                }
            }
        }
        logger.info("Предварительная загрузка метаданных завершена для ${connection.endpoint.url}")
    }

    private suspend fun batchReadMetadataFromServer(
        connection: OpcUaConnectionManager.ClientConnection,
        nodeIds: List<String>
    ): Map<String, NodeMetadata> {
        // TODO: Использовать OPC UA batch read для эффективного чтения
        val readValueIds = nodeIds.map { nodeId ->
            ReadValueId(NodeId.parse(nodeId), AttributeId.Value.uid(), null, QualifiedName.NULL_VALUE)
        }

        val dataValues = connection.client.read(0.0, TimestampsToReturn.Both, readValueIds).get().results
        return dataValues.withIndex().associate {
            nodeIds[it.index] to NodeMetadata(
                value = it.value.value?.value ?: "null",
                dataType = it.value.value?.dataType?.toString() ?: "unknown",
                timestamp = LocalDateTime.ofInstant(it.value.sourceTime?.javaInstant, ZoneId.of("Europe/Moscow")),
                serverTimestamp = LocalDateTime.ofInstant(it.value.serverTime?.javaInstant, ZoneId.of("Europe/Moscow"))
            )

        }

    }
}