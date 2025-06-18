package ru.iteco.opcua.model

import com.fasterxml.jackson.annotation.JsonFormat
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import org.springframework.data.relational.core.mapping.Table
import java.time.LocalDateTime

// Raw data model for MongoDB
@Document(collection = "\${spring.data.mongodb.collection}")
data class RawMeterData(
    @Id
    val id: String? = null,
    val nodeId: String,
    val value: Any,
    val dataType: String,
    val quality: String,
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    val timestamp: LocalDateTime,
    val serverTimestamp: LocalDateTime? = null
)

// Transformed data model for PostgreSQL TimeSeries
@Table("meter_timeseries")
data class MeterTimeSeries(
    @Id
    val id: Long? = null,
    val meterId: String,
    val meterType: MeterType,
    val value: Double,
    val unit: String,
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    val timestamp: LocalDateTime
)

enum class MeterType {
    HOT_WATER_FLOW,
    HOT_WATER_TEMPERATURE,
    COLD_WATER_FLOW,
    COLD_WATER_TEMPERATURE,
    HEAT_ENERGY,
    HEAT_POWER
}

// Kafka message model
data class KafkaMeterMessage(
    val deviceId: String,
    val sensorType: String,
    val measurement: Double,
    val unit: String,
    val location: String,
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
    val recordedAt: LocalDateTime,
    val metadata: Map<String, Any> = emptyMap()
)