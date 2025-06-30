package ru.iteco.opcua.service

import org.springframework.stereotype.Service
import ru.iteco.opcua.metadata.Metadata
import ru.iteco.opcua.model.KafkaMeterMessage
import ru.iteco.opcua.model.MeterTimeSeries
import ru.iteco.opcua.model.RawMeterData

@Service
class MeasurementEnricher{

    /**
     * Обогащение измерений метаданными для разных назначений
     */
    suspend fun enrichForMongo(rawData: RawMeterData): RawMeterData {
        // TODO: Минимальное обогащение для MongoDB (только основные метаданные)
        return rawData
    }

    suspend fun enrichForTimescale(rawData: RawMeterData): MeterTimeSeries? {
        // TODO: Обогащение для TimescaleDB (добавить controller/meter IDs, unit, resource type)
        return try {
            MeterTimeSeries(
                meterId = rawData.meterId,
                value = rawData.value.toString().toDouble(),
                timestamp = rawData.timestamp,
                uspdId = rawData.uspdId,
                subsystem = rawData.subsystem,
                resourceType = rawData.resourceType,
                nodeId = rawData.nodeId,
                serverTimestamp = rawData.serverTimestamp
            )
        } catch (_: NumberFormatException) {
            null
        }

    }

    suspend fun enrichForKafka(rawData: RawMeterData): KafkaMeterMessage? {
        // TODO: Максимальное обогащение для Kafka (все доступные метаданные)
        return try {
            KafkaMeterMessage(
                deviceId = rawData.uspdId?:"ERROR",
                sensorType = rawData.resourceType.toString(),
                measurement = rawData.value.toString().toDouble(),
                property = rawData.nodeId.substringAfterLast("."),
                recordedAt = rawData.timestamp,
                metadata = Metadata()
            )
        } catch (_: NumberFormatException) {
            null
        }
    }
}