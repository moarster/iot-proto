package ru.iteco.opcua.service


import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import ru.iteco.opcua.model.MeterType
import ru.iteco.opcua.model.RawMeterData
import java.time.LocalDateTime


class DataTransformationServiceTest {

    private lateinit var dataTransformationService: DataTransformationService

    @BeforeEach
    fun setUp() {
        dataTransformationService = DataTransformationService()
    }

    @Test
    fun `should transform hot water flow data to time series`() {
        // Given
        val rawData = RawMeterData(
            nodeId = "ns=2;s=HotWaterMeter.Flow",
            value = 15.5,
            dataType = "Double",
            quality = "good",
            timestamp = LocalDateTime.now()
        )

        // When
        val result = dataTransformationService.transformToTimeSeries(rawData)

        // Then
        assertNotNull(result)
        assertEquals("HWM_001", result!!.meterId)
        assertEquals(MeterType.HOT_WATER_FLOW, result.meterType)
        assertEquals(15.5, result.value)
        assertEquals("L/min", result.unit)
    }

    @Test
    fun `should transform temperature data to time series`() {
        // Given
        val rawData = RawMeterData(
            nodeId = "ns=2;s=ColdWaterMeter.Temperature",
            value = "22.3",
            dataType = "String",
            quality = "good",
            timestamp = LocalDateTime.now()
        )

        // When
        val result = dataTransformationService.transformToTimeSeries(rawData)

        // Then
        assertNotNull(result)
        assertEquals("CWM_001", result!!.meterId)
        assertEquals(MeterType.COLD_WATER_TEMPERATURE, result.meterType)
        assertEquals(22.3, result.value)
        assertEquals("°C", result.unit)
    }

    @Test
    fun `should transform heat meter energy data to time series`() {
        // Given
        val rawData = RawMeterData(
            nodeId = "ns=2;s=HeatMeter.Energy",
            value = 125.8,
            dataType = "Double",
            quality = "good",
            timestamp = LocalDateTime.now()
        )

        // When
        val result = dataTransformationService.transformToTimeSeries(rawData)

        // Then
        assertNotNull(result)
        assertEquals("HM_001", result!!.meterId)
        assertEquals(MeterType.HEAT_ENERGY, result.meterType)
        assertEquals(125.8, result.value)
        assertEquals("kWh", result.unit)
    }

    @Test
    fun `should return null for invalid node id`() {
        // Given
        val rawData = RawMeterData(
            nodeId = "ns=2;s=InvalidMeter.Unknown",
            value = 10.0,
            dataType = "Double",
            quality = "good",
            timestamp = LocalDateTime.now()
        )

        // When
        val result = dataTransformationService.transformToTimeSeries(rawData)

        // Then
        assertNull(result)
    }

    @Test
    fun `should handle non-numeric values gracefully`() {
        // Given
        val rawData = RawMeterData(
            nodeId = "ns=2;s=HotWaterMeter.Flow",
            value = "invalid",
            dataType = "String",
            quality = "good",
            timestamp = LocalDateTime.now()
        )

        // When
        val result = dataTransformationService.transformToTimeSeries(rawData)

        // Then
        assertNotNull(result)
        assertEquals(0.0, result!!.value)
    }

    @Test
    fun `should transform to kafka message correctly`() {
        // Given
        val rawData = RawMeterData(
            nodeId = "ns=2;s=HotWaterMeter.Flow",
            value = 25.0,
            dataType = "Double",
            quality = "good",
            timestamp = LocalDateTime.now()
        )

        // When
        val result = dataTransformationService.transformToKafkaMessage(rawData)

        // Then
        assertNotNull(result)
        assertEquals("DEVICE_HWM_001", result!!.deviceId)
        assertEquals("flow_sensor", result.sensorType)
        assertEquals(25.0, result.measurement)
        assertEquals("L/min", result.unit)
        assertEquals("Building_A", result.location)
        assertTrue(result.metadata.containsKey("nodeId"))
        assertTrue(result.metadata.containsKey("quality"))
    }

    @Test
    fun `should handle all meter types in kafka transformation`() {
        val testCases = listOf(
            "ns=2;s=HotWaterMeter.Temperature" to "temperature_sensor",
            "ns=2;s=ColdWaterMeter.Flow" to "flow_sensor",
            "ns=2;s=HeatMeter.Power" to "power_sensor",
            "ns=2;s=HeatMeter.Energy" to "energy_sensor"
        )

        testCases.forEach { (nodeId, expectedSensorType) ->
            // Given
            val rawData = RawMeterData(
                nodeId = nodeId,
                value = 10.0,
                dataType = "Double",
                quality = "good",
                timestamp = LocalDateTime.now()
            )

            // When
            val result = dataTransformationService.transformToKafkaMessage(rawData)

            // Then
            assertNotNull(result, "Failed for nodeId: $nodeId")
            assertEquals(expectedSensorType, result!!.sensorType, "Wrong sensor type for nodeId: $nodeId")
        }
    }
}