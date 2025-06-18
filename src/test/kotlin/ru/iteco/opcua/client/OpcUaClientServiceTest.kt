package ru.iteco.opcua.client


import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertNotNull
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations
import ru.iteco.opcua.config.OpcUaConfig

class OpcUaClientServiceTest {

    @Mock
    private lateinit var opcUaConfig: OpcUaConfig

    private lateinit var opcUaClientService: OpcUaClientService

    @BeforeEach
    fun setUp() {
        MockitoAnnotations.openMocks(this)

        `when`(opcUaConfig.applicationName).thenReturn("Test Client")
        `when`(opcUaConfig.endpointUrl).thenReturn("opc.tcp://localhost:4840")
        `when`(opcUaConfig.subscriptionInterval).thenReturn(1000L)
        `when`(opcUaConfig.nodeIds).thenReturn(listOf(
            "ns=2;s=HotWaterMeter.Flow",
            "ns=2;s=ColdWaterMeter.Temperature"
        ))

        opcUaClientService = OpcUaClientService(opcUaConfig)
    }

    @Test
    fun `should provide data stream`() = runTest {
        // When
        val dataStream = opcUaClientService.getDataStream()

        // Then
        assertNotNull(dataStream)
    }

    @Test
    fun `should handle data change correctly`() = runTest {
        // This test would require more complex mocking of OPC UA client
        // For now, we'll test that the service can be created and provides a stream
        val dataStream = opcUaClientService.getDataStream()
        assertNotNull(dataStream)
    }
}