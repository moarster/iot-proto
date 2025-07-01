package ru.iteco.opcua.service.messaging

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Service
import ru.iteco.opcua.model.KafkaMeterMessage

@Service
class KafkaMessagingService(
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val objectMapper: ObjectMapper
) {
    private val logger = LoggerFactory.getLogger(KafkaMessagingService::class.java)
    private val kafkaTopic = "meter-readings"

    suspend fun send(message: KafkaMeterMessage): Result<Unit> {
        return try {
            val messageJson = objectMapper.writeValueAsString(message)
            kafkaTemplate.send(kafkaTopic, message.deviceId, messageJson)
            logger.debug("Отправлено сообщение в Kafka для: {}", message.deviceId)
            Result.success(Unit)
        } catch (e: Exception) {
            logger.error("Ошибка отправки сообщения в Kafka", e)
            Result.failure(e)
        }
    }
}