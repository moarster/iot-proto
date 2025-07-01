package ru.iteco.opcua.service.persistence

import kotlinx.coroutines.reactor.awaitSingle
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import ru.iteco.opcua.model.MeterTimeSeries
import ru.iteco.opcua.repository.MeterTimeSeriesRepository

@Service
class PostgresDataPersistenceService(
    private val meterTimeSeriesRepository: MeterTimeSeriesRepository
) {
    private val logger = LoggerFactory.getLogger(PostgresDataPersistenceService::class.java)

    suspend fun save(timeSeries: MeterTimeSeries): Result<MeterTimeSeries> {
        return try {
            val savedData = meterTimeSeriesRepository.save(timeSeries).awaitSingle()
            logger.debug("Сохранено значение во временной ряд PostgreSQL: {}", savedData.id)
            Result.success(savedData)
        } catch (e: Exception) {
            logger.error("Ошибка сохранения в PostgreSQL", e)
            Result.failure(e)
        }
    }
}