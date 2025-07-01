package ru.iteco.opcua.model

import com.fasterxml.jackson.annotation.JsonFormat
import com.fasterxml.jackson.annotation.JsonUnwrapped
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import org.springframework.data.relational.core.mapping.Table
import ru.iteco.opcua.model.Metadata
import java.time.LocalDateTime

@Document(collection = "\${spring.data.mongodb.collection}")
data class RawMeterData(
    @Id
    val id: String? = null,
    val uspdId: String? = null,
    val meterId: String? = null, // Уровень счетчика
    val subsystem: String? = null, // Уровень логического узла
    val resourceType: MeterType? = MeterType.HEAT,
    val isMeasurement: Boolean = false,
    val nodeId: String,
    val endpointUrl: String,
    val value: Any,
    val dataType: String,
    val unit: String? = null,
    val quality: String,
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    val timestamp: LocalDateTime,
    val serverTimestamp: LocalDateTime? = null,
    val periodStart: LocalDateTime? = null,
    val periodEnd: LocalDateTime? = null,
)

@Table("meter_timeseries")
data class MeterTimeSeries(
    @Id
    val id: Long? = null,
    val uspdId: String,
    val meterId: String,
    val subsystem: String,
    val meterType: MeterType,
    val property: String,
    val nodeId: String,
    val value: Double,
    val unit: String? =null,
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    val timestamp: LocalDateTime,
    val serverTimestamp: LocalDateTime?,
)

//TODO: Привести к реальным требованиям
data class KafkaMeterMessage(
    val deviceId: String,
    val sensorType: String,
    val measurement: Double,
    val unit: String?=null,
    val property: String, // Property name
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
    val recordedAt: LocalDateTime,
    @JsonUnwrapped
    val metadata: Metadata = Metadata()
)

enum class MeterType{
    GVS,HVS,HEAT;
    companion object {
        fun fromString(input: String): MeterType? {
            val upper = input.uppercase()
            return when {
                "ГВС" in upper -> GVS
                "ХВС" in upper -> HVS
                "ЦО" in upper  -> HEAT
                else -> null
            }
        }
    }
}
