package ru.iteco.opcua.metadata

import java.time.LocalDateTime

/**
 * Стратегия кэширования для разных типов метаданных
 */
enum class MetadataCacheStrategy(val ttlHours: Long, val refreshStrategy: RefreshStrategy) {
    CONTROLLER_STATIC(24 * 7, RefreshStrategy.ON_CONNECT), // Статические данные контроллера - раз в неделю
    METER_STATIC(24, RefreshStrategy.ON_CONNECT),           // Статические данные счетчика - раз в день
    METER_DYNAMIC(1, RefreshStrategy.ON_CHANGE),           // Динамические данные (статус подключения) - по изменению
    SUBSYSTEM_STATIC(24, RefreshStrategy.ON_CONNECT)       // Данные подсистемы - раз в день
}

enum class RefreshStrategy {
    ON_CONNECT,    // Обновлять при подключении
    ON_CHANGE,     // Обновлять при изменении
    PERIODIC       // Периодическое обновление
}

/**
 * Классификация узлов OPC UA
 */
data class NodeClassification(
    val nodeId: String,
    val meterId: String? = null,
    val subsystem: String? = null,
    val nodeType: NodeType,
    val cacheStrategy: MetadataCacheStrategy ?= null,
    val isMeasurement: Boolean = false
) {
    enum class NodeType {
        CONTROLLER_METADATA,
        METER_METADATA_STATIC,
        METER_METADATA_DYNAMIC,
        SUBSYSTEM_METADATA,
        MEASUREMENT_CURRENT,
        MEASUREMENT_HISTORICAL,
        UNCLASSIFIED,
    }
}

data class NodeMetadata(
    val value: Any,
    val dataType: String,
    val timestamp: LocalDateTime,
    val serverTimestamp: LocalDateTime?
)

data class MetadataUpdate(
    val endpointUrl: String,
    val nodeId: String,
    val metadata: NodeMetadata,
    val updateType: MetadataUpdateType
)

enum class MetadataUpdateType {
    METER_STATUS_CHANGED,
    METER_DISCONNECTED,
    METER_CONNECTED,
    CONFIGURATION_CHANGED
}

