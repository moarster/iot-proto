package ru.iteco.opcua.service

import org.springframework.stereotype.Service
import ru.iteco.opcua.model.KafkaMeterMessage
import ru.iteco.opcua.model.MeterTimeSeries
import ru.iteco.opcua.model.RawMeterData

@Service
class DataTransformationService {

    fun transformToTimeSeries(rawData: RawMeterData): MeterTimeSeries? {
        return try {
            val (meterType, unit) = parseMeterInfo(rawData.nodeId)
            val numericValue = parseNumericValue(rawData.value)

            MeterTimeSeries(
                meterId = extractMeterId(rawData.nodeId),
                meterType = meterType,
                value = numericValue,
                unit = unit,
                timestamp = rawData.timestamp
            )
        } catch (e: Exception) {
            null // Skip invalid data
        }
    }

    fun transformToKafkaMessage(rawData: RawMeterData): KafkaMeterMessage? {
        return try {
            val deviceId = extractDeviceId(rawData.nodeId)
            val sensorType = extractSensorType(rawData.nodeId)
            val numericValue = parseNumericValue(rawData.value)
            val unit = determineUnit(rawData.nodeId)

            KafkaMeterMessage(
                deviceId = deviceId,
                sensorType = sensorType,
                measurement = numericValue,
                unit = unit,
                location = "Building_A", // Example location
                recordedAt = rawData.timestamp,
                metadata = mapOf(
                    "nodeId" to rawData.nodeId,
                    "dataType" to rawData.dataType,
                    "quality" to rawData.quality
                )
            )
        } catch (e: Exception) {
            null // Skip invalid data
        }
    }

    private fun parseMeterInfo(nodeId: String): Pair<MeterType, String> {
        return when {
            nodeId.contains("HotWaterMeter.Flow") -> MeterType.HOT_WATER_FLOW to "L/min"
            nodeId.contains("HotWaterMeter.Temperature") -> MeterType.HOT_WATER_TEMPERATURE to "°C"
            nodeId.contains("ColdWaterMeter.Flow") -> MeterType.COLD_WATER_FLOW to "L/min"
            nodeId.contains("ColdWaterMeter.Temperature") -> MeterType.COLD_WATER_TEMPERATURE to "°C"
            nodeId.contains("HeatMeter.Energy") -> MeterType.HEAT_ENERGY to "kWh"
            nodeId.contains("HeatMeter.Power") -> MeterType.HEAT_POWER to "kW"
            else -> throw IllegalArgumentException("Unknown meter type: $nodeId")
        }
    }

    private fun extractMeterId(nodeId: String): String {
        return when {
            nodeId.contains("HotWaterMeter") -> "HWM_001"
            nodeId.contains("ColdWaterMeter") -> "CWM_001"
            nodeId.contains("HeatMeter") -> "HM_001"
            else -> "UNKNOWN"
        }
    }

    private fun extractDeviceId(nodeId: String): String {
        return "DEVICE_" + extractMeterId(nodeId)
    }

    private fun extractSensorType(nodeId: String): String {
        return when {
            nodeId.contains("Flow") -> "flow_sensor"
            nodeId.contains("Temperature") -> "temperature_sensor"
            nodeId.contains("Energy") -> "energy_sensor"
            nodeId.contains("Power") -> "power_sensor"
            else -> "unknown_sensor"
        }
    }

    private fun determineUnit(nodeId: String): String {
        return when {
            nodeId.contains("Flow") -> "L/min"
            nodeId.contains("Temperature") -> "celsius"
            nodeId.contains("Energy") -> "kWh"
            nodeId.contains("Power") -> "kW"
            else -> "unknown"
        }
    }

    private fun parseNumericValue(value: Any): Double {
        return when (value) {
            is Number -> value.toDouble()
            is String -> value.toDoubleOrNull() ?: 0.0
            else -> 0.0
        }
    }
}