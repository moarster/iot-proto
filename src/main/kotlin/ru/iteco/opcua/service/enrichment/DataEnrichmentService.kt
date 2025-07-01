package ru.iteco.opcua.service.enrichment

import org.springframework.stereotype.Service
import ru.iteco.opcua.model.KafkaMeterMessage
import ru.iteco.opcua.model.MeterTimeSeries
import ru.iteco.opcua.model.MeterType
import ru.iteco.opcua.model.RawMeterData

@Service
class DataEnrichmentService {

    //TODO: Перенести сюда насыщение метаданными
    fun enrichForMongo(rawData: RawMeterData): RawMeterData {
        return rawData.copy(
            nodeId = rawData.nodeId.trim(),
            quality = rawData.quality.lowercase()
        )
    }

    fun enrichForTimescale(rawData: RawMeterData): MeterTimeSeries? {
        return try {
            // Только измерения
            // TODO: Перенести эту проверку до вызова функции
            if (!rawData.isMeasurement) return null

            val numericValue = when (rawData.value) {
                is Number -> rawData.value.toDouble()
                is String -> rawData.value.toDoubleOrNull() ?: return null
                else -> return null
            }

            MeterTimeSeries(
                uspdId = rawData.uspdId ?: return null,
                meterId = rawData.meterId ?: extractMeterIdFromNodeId(rawData.nodeId),
                meterType = rawData.resourceType ?: MeterType.HEAT,
                subsystem = rawData.subsystem ?: "1",
                property = extractPropertyFromNodeId(rawData.nodeId),
                nodeId = rawData.nodeId,
                value = numericValue,
                unit = rawData.unit,
                timestamp = rawData.timestamp,
                serverTimestamp = rawData.serverTimestamp
            )
        } catch (_: NumberFormatException) {
            null // Ожидаемая ошибка, но все равно костыль
        }
    }

    fun enrichForKafka(rawData: RawMeterData): KafkaMeterMessage? {
        return try {
            val numericValue = when (rawData.value) {
                is Number -> rawData.value.toDouble()
                is String -> rawData.value.toDoubleOrNull() ?: return null
                else -> return null
            }

            KafkaMeterMessage(
                deviceId = rawData.uspdId ?: "UNKNOWN_DEVICE",
                sensorType = rawData.resourceType?.name ?: "UNKNOWN",
                measurement = numericValue,
                unit = rawData.unit,
                property = extractPropertyFromNodeId(rawData.nodeId),
                recordedAt = rawData.timestamp,
                // TODO: Add proper metadata enrichment
                // metadata = ...
            )
        } catch (_: NumberFormatException) {
            null // Ожидаемая ошибка, но все равно костыль
        }
    }

    //TODO: реализовать правильно
    private fun extractMeterIdFromNodeId(nodeId: String): String {
        return nodeId.substringAfterLast(".").substringBefore("[")
    }

    private fun extractPropertyFromNodeId(nodeId: String): String {
        return nodeId.substringAfterLast(".")
    }
}