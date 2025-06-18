package ru.iteco.opcua.service


import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mock
import org.mockito.Mockito.*
import org.mockito.MockitoAnnotations
import org.springframework.kafka.core.KafkaTemplate
import reactor.core.publisher.Mono
import ru.iteco.opcua.model.KafkaMeterMessage
import ru.iteco.opcua.model.MeterTimeSeries
import ru.iteco.opcua.model.MeterType
import ru.iteco.opcua.model.RawMeterData
import ru.iteco.opcua.repository.MeterTimeSeriesRepository
import ru.iteco.opcua.repository.RawMeterDataRepository
import java.time.LocalDateTime


class DataProcessingServiceTest {

    @Mock
    private lateinit var rawMeterDataRepository: RawMeterDataRepository

    @Mock
    private lateinit var meterTimeSeriesRepository: MeterTimeSeriesRepository

    @Mock
    private lateinit var dataTransformationService: DataTransformationService

    @Mock
    private lateinit var kafkaTemplate: KafkaTemplate<String, String>

    @Mock
    private lateinit var objectMapper: ObjectMapper

    private lateinit var dataProcessingService: DataProcessingService

    @BeforeEach
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        dataProcessingService = DataProcessingService(
            rawMeterDataRepository,
            meterTimeSeriesRepository,
            dataTransformationService,
            kafkaTemplate,
            objectMapper
        )
    }

    @Test
    fun `should process data successfully to all three destinations`() = runTest {
        // Given
        val rawData = RawMeterData(
            nodeId = "ns=2;s=HotWaterMeter.Flow",
            value = 15.5,
            dataType = "Double",
            quality = "good",
            timestamp = LocalDateTime.now()
        )

        val savedRawData = rawData.copy(id = "mongo-id-123")
        val timeSeries = MeterTimeSeries(
            id = 1L,
            meterId = "HWM_001",
            meterType = MeterType.HOT_WATER_FLOW,
            value = 15.5,
            unit = "L/min",
            timestamp = rawData.timestamp
        )
        val kafkaMessage = KafkaMeterMessage(
            deviceId = "DEVICE_HWM_001",
            sensorType = "flow_sensor",
            measurement = 15.5,
            unit = "L/min",
            location = "Building_A",
            recordedAt = rawData.timestamp
        )

        // Mock repository responses
        `when`(rawMeterDataRepository.save(any(RawMeterData::class.java))).thenReturn(Mono.just(savedRawData))
        `when`(dataTransformationService.transformToTimeSeries(rawData)).thenReturn(timeSeries)
        `when`(meterTimeSeriesRepository.save(any(MeterTimeSeries::class.java))).thenReturn(Mono.just(timeSeries))
        `when`(dataTransformationService.transformToKafkaMessage(rawData)).thenReturn(kafkaMessage)
        `when`(objectMapper.writeValueAsString(kafkaMessage)).thenReturn("{\"deviceId\":\"DEVICE_HWM_001\"}")

        // When
        dataProcessingService.processData(rawData)

        // Then
        verify(rawMeterDataRepository).save(rawData)
        verify(dataTransformationService).transformToTimeSeries(rawData)
        verify(meterTimeSeriesRepository).save(timeSeries)
        verify(dataTransformationService).transformToKafkaMessage(rawData)
        verify(kafkaTemplate).send("meter-readings", "DEVICE_HWM_001", "{\"deviceId\":\"DEVICE_HWM_001\"}")
    }

    @Test
    fun `should handle mongo save failure gracefully`() = runTest {
        // Given
        val rawData = RawMeterData(
            nodeId = "ns=2;s=HotWaterMeter.Flow",
            value = 15.5,
            dataType = "Double",
            quality = "good",
            timestamp = LocalDateTime.now()
        )

        `when`(rawMeterDataRepository.save(any(RawMeterData::class.java))).thenReturn(Mono.error(RuntimeException("MongoDB error")))
        `when`(dataTransformationService.transformToTimeSeries(rawData)).thenReturn(null)
        `when`(dataTransformationService.transformToKafkaMessage(rawData)).thenReturn(null)

        // When & Then - should not throw exception
        dataProcessingService.processData(rawData)

        verify(rawMeterDataRepository).save(rawData)
    }

    @Test
    fun `should skip postgres save when transformation returns null`() = runTest {
        // Given
        val rawData = RawMeterData(
            nodeId = "ns=2;s=InvalidMeter.Unknown",
            value = 15.5,
            dataType = "Double",
            quality = "good",
            timestamp = LocalDateTime.now()
        )

        `when`(rawMeterDataRepository.save(any(RawMeterData::class.java))).thenReturn(Mono.just(rawData))
        `when`(dataTransformationService.transformToTimeSeries(rawData)).thenReturn(null)
        `when`(dataTransformationService.transformToKafkaMessage(rawData)).thenReturn(null)

        // When
        dataProcessingService.processData(rawData)

        // Then
        verify(dataTransformationService).transformToTimeSeries(rawData)
        verify(meterTimeSeriesRepository, never()).save(any(MeterTimeSeries::class.java))
        verify(kafkaTemplate, never()).send(any(String::class.java), any(String::class.java), any(String::class.java))
    }
}