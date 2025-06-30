package ru.iteco.opcua.model

import com.fasterxml.jackson.annotation.JsonFormat
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import org.springframework.data.relational.core.mapping.Table
import ru.iteco.opcua.metadata.Metadata
import java.time.LocalDateTime

/**
 * Raw data model for MongoDB storage.
 *
 * This model is designed to be flexible and store raw OPC UA values as they are received.
 * The actual parsing and structuring into more specific types (MeterType and units)
 * is handled by the DataTransformationService.
 *
 * @property id Unique identifier for the document in MongoDB (auto-generated if not provided)
 * @property nodeId The OPC UA Node ID string (e.g., "ns=2;s=HeatMeteringSubsystem.Q1")
 * @property value The raw value from OPC UA, can be Double, String, Boolean, etc.
 * @property dataType Data type reported by OPC UA (e.g., "Double", "UInt32")
 * @property quality Quality indicator of the data (e.g., "good", "bad", "uncertain")
 * @property timestamp Local timestamp when the data was received by the client
 * @property serverTimestamp Optional timestamp from the OPC UA server if available
 */
@Document(collection = "\${spring.data.mongodb.collection}")
data class RawMeterData(
    @Id
    val id: String? = null,
    val uspdId: String?,
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

/**
 * Transformed data model for storing time series data in PostgreSQL.
 *
 * This model represents processed meter readings that have been transformed from raw OPC UA data
 * into a structured format suitable for time series analysis and reporting.
 *
 * @property id Unique identifier for the time series record (auto-generated)
 * @property meterId Identifier for the specific meter (extracted from NodeId parsing)
 * @property resourceType The specific type of measurement (e.g., GVS_TEMP_SUPPLY)
 * @property value The measured value, converted to Double for consistent processing
 * @property unit Unit of measurement (e.g., "°C", "м3/ч", "Гкал")
 * @property timestamp The exact timestamp when the measurement was taken
 */
@Table("meter_timeseries")
data class MeterTimeSeries(
    @Id
    val id: Long? = null,
    val uspdId: String?,
    val meterId: String?,
    val subsystem: String?,
    val resourceType: MeterType?,
    val nodeId: String,
    val value: Double,
    val unit: String? =null,
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    val timestamp: LocalDateTime,
    val serverTimestamp: LocalDateTime?,
)

//
/**
 * Kafka message model
 *
 * @property deviceId Unique ID of the device (e.g., meter serial number or derived from NodeId)
 * @property sensorType Type of sensor or measurement (e.g., "GVS_TEMP_SUPPLY", "HEAT_ENERGY_ACCUMULATED")
 * @property measurement The actual measured value
 * @property unit Unit of measurement
 * @property location Physical location of the meter/sensor
 * @property recordedAt Timestamp of the recording
 * @property metadata Additional metadata
 */
data class KafkaMeterMessage(
    val deviceId: String,
    val sensorType: String,
    val measurement: Double,
    val unit: String?=null,
    val property: String, // Property name
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
    val recordedAt: LocalDateTime,
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
