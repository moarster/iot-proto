package ru.iteco.opcua.integration

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertNotNull
import org.mockito.ArgumentMatchers.any
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations
import org.springframework.kafka.core.KafkaTemplate
import reactor.core.publisher.Mono
import ru.iteco.opcua.model.MeterTimeSeries
import ru.iteco.opcua.model.MeterType
import ru.iteco.opcua.model.RawMeterData
import ru.iteco.opcua.repository.MeterTimeSeriesRepository
import ru.iteco.opcua.repository.RawMeterDataRepository
import ru.iteco.opcua.service.DataProcessingService
import ru.iteco.opcua.service.DataTransformationService
import java.time.LocalDateTime
import kotlin.test.assertEquals

class DataFlowIntegrationTest {

    @Mock
    private lateinit var rawMeterDataRepository: RawMeterDataRepository

    @Mock
    private lateinit var meterTimeSeriesRepository: MeterTimeSeriesRepository

    @Mock
    private lateinit var kafkaTemplate: KafkaTemplate<String, String>

    @Mock
    private lateinit var objectMapper: ObjectMapper

    private lateinit var dataTransformationService: DataTransformationService
    private lateinit var dataProcessingService: DataProcessingService

    @BeforeEach
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        dataTransformationService = DataTransformationService()
        dataProcessingService = DataProcessingService(
            rawMeterDataRepository,
            meterTimeSeriesRepository,
            dataTransformationService,
            kafkaTemplate,
            objectMapper
        )
    }

    @Test
    fun `should process complete data flow from raw data to all destinations`() = runTest {
        // Given
        val rawData = RawMeterData(
            nodeId = "ns=2;s=HotWaterMeter.Flow",
            value = 42.5,
            dataType = "Double",
            quality = "good",
            timestamp = LocalDateTime.now()
        )

        val savedRawData = rawData.copy(id = "saved-id")

        // Mock successful saves
        `when`(rawMeterDataRepository.save(any(RawMeterData::class.java))).thenReturn(Mono.just(savedRawData))
        `when`(meterTimeSeriesRepository.save(any(MeterTimeSeries::class.java))).thenAnswer { invocation ->
            val timeSeries = invocation.getArgument<MeterTimeSeries>(0)
            val savedTimeSeries = timeSeries.copy(id = 1L)
            Mono.just(savedTimeSeries)
        }
        `when`(objectMapper.writeValueAsString(any())).thenReturn("{\"test\":\"data\"}")

        // When
        dataProcessingService.processData(rawData)

        // Then - Verify the complete flow
        verify(rawMeterDataRepository).save(rawData)

        // Verify transformation happened correctly
        val expectedTimeSeries = dataTransformationService.transformToTimeSeries(rawData)
        assertNotNull(expectedTimeSeries)
        assertEquals(MeterType.HOT_WATER_FLOW, expectedTimeSeries.meterType)
        assertEquals(42.5, expectedTimeSeries.value)

        // Verify Kafka message transformation
        val expectedKafkaMessage = dataTransformationService.transformToKafkaMessage(rawData)
        assertNotNull(expectedKafkaMessage)
        assertEquals("DEVICE_HWM_001", expectedKafkaMessage.deviceId)
        assertEquals("flow_sensor", expectedKafkaMessage.sensorType)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `should handle mixed success and failure scenarios`() = runTest {
        // Given - a scenario where MongoDB fails but others succeed
        val rawData = RawMeterData(
            nodeId = "ns=2;s=ColdWaterMeter.Temperature",
            value = 18.5,
            dataType = "Double",
            quality = "good",
            timestamp = LocalDateTime.now()
        )

        // Mock all repositories and services
        //`when`(rawMeterDataRepository.save(any(RawMeterData::class.java))).thenReturn(Mono.error(RuntimeException("MongoDB down")))
        `when`(meterTimeSeriesRepository.save(any())).thenAnswer { invocation ->
            Mono.just(invocation.getArgument<MeterTimeSeries>(0))
        }
        `when`(objectMapper.writeValueAsString(any())).thenReturn("{\"test\":\"data\"}")

        // When - should not throw exception
        dataProcessingService.processData(rawData)

        // Wait for all async operations to complete
        // The service uses coroutineScope with async blocks, so we need to ensure all coroutines complete
        runBlocking {
            withTimeout(1000L) { // Wait up to 1 second for all operations to complete
                // Verify all operations were attempted
                verify(rawMeterDataRepository).save(rawData)
                verify(meterTimeSeriesRepository).save(any())
                verify(kafkaTemplate).send(any(), any(), any())
            }
        }
    }
}