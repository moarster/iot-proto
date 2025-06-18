package ru.iteco.opcua.repository

import org.springframework.data.mongodb.repository.ReactiveMongoRepository
import org.springframework.stereotype.Repository
import ru.iteco.opcua.model.RawMeterData

@Repository
interface RawMeterDataRepository : ReactiveMongoRepository<RawMeterData, String>
