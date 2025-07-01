package ru.iteco.opcua.model

data class MeterDataEvent(
    val rawData: RawMeterData,
    val enrichedForMongo: RawMeterData? = null,
    val enrichedForTimescale: MeterTimeSeries? = null,
    val enrichedForKafka: KafkaMeterMessage? = null
)