package ru.iteco.opcua.service.persistence

import kotlinx.coroutines.reactor.awaitSingle
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import ru.iteco.opcua.model.RawMeterData
import ru.iteco.opcua.repository.RawMeterDataRepository

@Service
class MongoDataPersistenceService(
    private val rawMeterDataRepository: RawMeterDataRepository
) {
    private val logger = LoggerFactory.getLogger(MongoDataPersistenceService::class.java)

    suspend fun save(rawData: RawMeterData): Result<RawMeterData> {
        return try {
            val savedData = rawMeterDataRepository.save(rawData).awaitSingle()
            logger.debug("Данные сохранены в MongoDB: {}", savedData.id)
            Result.success(savedData)
        } catch (e: Exception) {
            logger.error("Ошибка сохранения данных в MongoDB", e)
            Result.failure(e)
        }
    }
}