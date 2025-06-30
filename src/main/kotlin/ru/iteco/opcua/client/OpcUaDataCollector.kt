package ru.iteco.opcua.client

import jakarta.annotation.PostConstruct
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import org.apache.kafka.shaded.com.google.protobuf.LazyStringArrayList.emptyList
import org.eclipse.milo.opcua.stack.core.AttributeId
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId
import org.eclipse.milo.opcua.stack.core.types.builtin.QualifiedName
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned
import org.eclipse.milo.opcua.stack.core.types.enumerated.MonitoringMode
import org.eclipse.milo.opcua.stack.core.types.enumerated.TimestampsToReturn
import org.eclipse.milo.opcua.stack.core.types.structured.MonitoredItemCreateRequest
import org.eclipse.milo.opcua.stack.core.types.structured.MonitoringParameters
import org.eclipse.milo.opcua.stack.core.types.structured.ReadValueId
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import ru.iteco.opcua.config.Endpoint
import ru.iteco.opcua.config.NodeIdsConfig
import ru.iteco.opcua.config.OpcUaConfig
import ru.iteco.opcua.metadata.*
import ru.iteco.opcua.model.RawMeterData
import java.time.LocalDateTime
import java.time.ZoneId

@Service
class OpcUaDataCollector(
    private val connectionManager: OpcUaConnectionManager,
    private val metadataService: MetadataService,
    private val nodeClassifier: NodeClassifier,
    private val nodeIdsConfig: NodeIdsConfig,
    private val opcUaConfig: OpcUaConfig,
) {
    private val logger = LoggerFactory.getLogger(OpcUaDataCollector::class.java)

    private val measurementChannel = Channel<RawMeterData>(Channel.UNLIMITED)
    private val metadataUpdateChannel = Channel<MetadataUpdate>(Channel.UNLIMITED)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @PostConstruct
    fun initialize() {
        scope.launch {
            connectionManager.initializeConnections()
            startDataCollection()
        }
    }

    private suspend fun startDataCollection() {
        connectionManager.getConnectedClients().forEach { connection ->
            scope.launch {
                setupDataCollection(connection)
            }
        }
    }

    /**
     * Сначала пробуем подписаться, если не получается, то начинаем опрос
     */
    private suspend fun setupDataCollection(connection: OpcUaConnectionManager.ClientConnection) {
        try {
            if (!setupSmartSubscription(connection)) {
                logger.warn("Не удалось подписаться на ${connection.endpoint}, начинаем опрос")
                startPolling(connection)
            }
        } catch (e: Exception) {
            logger.error("Ошибки сбора данных с ${connection.endpoint}", e)
        }
    }
    /**
     * Настройка подписки с разделением метаданных и измерений
     */
    private suspend fun setupSmartSubscription(connection: OpcUaConnectionManager.ClientConnection): Boolean =
    withContext(Dispatchers.IO)  {
        try {
            // Строим список узлов для подписки (БЕЗ статических метаданных!)
            val measurementNodes = buildMeasurementNodeIds(connection.endpoint)
            val dynamicMetadataNodes = emptyList() //TODO: определить динамические метаданные
            //TODO: На статические метаданные тоже надо подписываться

            val subscriptionNodes = measurementNodes + dynamicMetadataNodes

            // Создаем подписку OPC UA
            val subscription = connection.client.subscriptionManager
                .createSubscription(opcUaConfig.subscriptionInterval.toDouble())
                .get()

            //Создаем мониторинг
            val monitoredItems = subscriptionNodes.map { nodeIdString ->
                val readValueId = ReadValueId(
                    NodeId.parse(nodeIdString),
                    AttributeId.Value.uid(),
                    null,
                    QualifiedName.NULL_VALUE
                )

                val monitoringParameters = MonitoringParameters(
                    Unsigned.uint(1),
                    opcUaConfig.subscriptionInterval.toDouble(),
                    null,
                    Unsigned.uint(10),
                    true
                )

                MonitoredItemCreateRequest(
                    readValueId,
                    MonitoringMode.Reporting,
                    monitoringParameters
                )
            }

            val createdItems = subscription.createMonitoredItems(
                TimestampsToReturn.Both,
                monitoredItems
            ).get()

            var successCount = 0
            createdItems.forEach { item ->
                if (item.statusCode.isGood) {
                    item.setValueConsumer { _, value ->
                        scope.launch {
                            //handleDataChange(connection.endpoint.url, item, value)
                            handleSubscriptionValue(connection, item.readValueId.nodeId.toParseableString(), value)
                        }
                    }
                    successCount++
                } else {
                    logger.debug("Failed to monitor {} on {}", item.readValueId.nodeId, connection.endpoint)
                }
            }
            // TODO: 3. Настроить подписку с разными обработчиками
            //setupSubscriptionWithHandlers(connection, subscriptionNodes)
            logger.debug("Created {}/{} monitored items for {}", successCount, monitoredItems.size, connection.endpoint)
            true
        } catch (e: Exception) {
            logger.error("Не удалось создать подписку на ${connection.endpoint}: ${e.message}")
            false
        }

    }

    private suspend fun setupSubscriptionWithHandlers(
        connection: OpcUaConnectionManager.ClientConnection,
        nodeIds: List<String>
    ) {
        // TODO: Создать подписку, в обработчике определять тип узла и направлять в нужный канал
    }

    private suspend fun handleSubscriptionValue(
        connection: OpcUaConnectionManager.ClientConnection,
        nodeId: String,
        value: DataValue
    ) {
        val classification = nodeClassifier.classifyNode(nodeId)

        if (classification.isMeasurement) {
            // ЭТО ИЗМЕРЕНИЕ → обрабатываем как данные
            handleMeasurementValue(connection, nodeId, value, classification)
        } else {
            // ЭТО МЕТАДАННЫЕ → обновляем кэш
            handleMetadataChange(connection, nodeId, value, classification)
        }
    }

    private suspend fun handleMeasurementValue(
        connection: OpcUaConnectionManager.ClientConnection,
        nodeId: String,
        value: DataValue,
        classification: NodeClassification
    ) {
        // TODO: 1. Получить необходимые метаданные из кэша (быстро)
        // TODO: 2. Создать RawMeterData с полным контекстом
        // TODO: 3. Отправить в канал измерений
        // Получаем метаданные из кэша (БЫСТРО!)
        val metadata = metadataService.getMeasurementMetadata(connection.endpoint.url,classification)

        // Создаем RawMeterData с полным контекстом
        val rawData = RawMeterData(
            nodeId = nodeId,
            endpointUrl = connection.endpoint.url,
            value = value.value?.value ?: "null",
            uspdId = metadata.uspdId,
            meterId = classification.meterId,
            subsystem = classification.subsystem,
            resourceType = metadata.resourceType,
            isMeasurement = classification.isMeasurement,
            dataType = value.value?.dataType?.toString() ?: "unknown",
            quality = when {
                value.statusCode?.isGood ?: false -> "good"
                value.statusCode?.isBad ?: false -> "bad"
                value.statusCode?.isUncertain ?: false -> "uncertain"
                else -> "unknown"
            },
            timestamp = LocalDateTime.ofInstant(value.sourceTime?.javaInstant, ZoneId.of("Europe/Moscow")),
            serverTimestamp = LocalDateTime.ofInstant(value.serverTime?.javaInstant, ZoneId.of("Europe/Moscow")),
        )

        // Отправляем в канал для дальнейшей обработки
        measurementChannel.send(rawData)  // ← В канал!
    }

    private suspend fun handleMetadataChange(
        connection: OpcUaConnectionManager.ClientConnection,
        nodeId: String,
        value: DataValue,
        classification: NodeClassification
    ) {
        // TODO: 1. Обновить кэш метаданных
        // TODO: 2. Если это критичные метаданные - отправить уведомление в канал
        // Обновляем кэш
        val metadata = NodeMetadata(
            value = value.value?.value ?: "null",
            dataType = value.value?.dataType?.toString() ?: "unknown",
            timestamp = LocalDateTime.ofInstant(value.sourceTime?.javaInstant, ZoneId.of("Europe/Moscow")),
            serverTimestamp = LocalDateTime.ofInstant(value.serverTime?.javaInstant, ZoneId.of("Europe/Moscow")),
        )
        metadataService.cacheMetadata(connection.endpoint.url, nodeId, metadata)

        // TODO: Не все отправлять, а только критичные
        if (true) {
            val update = MetadataUpdate(connection.endpoint.url, nodeId, metadata, MetadataUpdateType.CONFIGURATION_CHANGED)
            metadataUpdateChannel.send(update)
        }
    }



    private fun startPolling(connection: OpcUaConnectionManager.ClientConnection) {
        scope.launch {
            while (connection.isConnected) {
                try {
                    pollMeasurementNodes(connection)
                    delay(opcUaConfig.subscriptionInterval)
                } catch (e: Exception) {
                    logger.error("Polling error for ${connection.endpoint.url}", e)
                    delay(5000)
                }
            }
        }
    }

    private suspend fun pollMeasurementNodes(connection: OpcUaConnectionManager.ClientConnection) =
        withContext(Dispatchers.IO) {
            try {
                val measurementNodes = buildMeasurementNodeIds(connection.endpoint)
                if (measurementNodes.isEmpty()) {
                    logger.warn("No measurement nodes configured for ${connection.endpoint}")
                    return@withContext
                }

                val nodeIds = measurementNodes.map { NodeId.parse(it) }
                val readValueIds = nodeIds.map { nodeId ->
                    ReadValueId(nodeId, AttributeId.Value.uid(), null, QualifiedName.NULL_VALUE)
                }

                val dataValues = connection.client.read(0.0, TimestampsToReturn.Both, readValueIds).get().results

                dataValues.forEachIndexed { index, dataValue ->
                    if (dataValue.statusCode?.isGood == true) {
                        val nodeIdString = measurementNodes[index]
                        handleMeasurementValue(connection, nodeIdString, dataValue, nodeClassifier.classifyNode(nodeIdString))
                    }
                }

            } catch (e: Exception) {
                logger.error("Polling failed for ${connection.endpoint}", e)
                throw e
            }
        }

    /**
     * Builds only measurement node IDs (Current.* and History.*)
     */
    private fun buildMeasurementNodeIds(endpoint: Endpoint): List<String> {
        val nodeIds = mutableListOf<String>()
        val nodePrefix = "ns=2;s=GIUSController"

        endpoint.meters.forEach { meter ->
            meter.subs.forEach { sub ->
                // Current measurements
                nodeIdsConfig.current.forEach { valueNode ->
                    nodeIds.add("$nodePrefix.${meter.guid}.$sub.Current.$valueNode")
                }

                // Historical measurements
                nodeIdsConfig.history.forEach { valueNode ->
                    nodeIds.add("$nodePrefix.${meter.guid}.$sub.History.$valueNode")
                }
            }
        }

        return nodeIds
    }

    // Публичные API
    fun getMetadataUpdateStream(): Flow<MetadataUpdate> = metadataUpdateChannel.receiveAsFlow()
    fun getDataStream(): Flow<RawMeterData> = measurementChannel.receiveAsFlow()

}