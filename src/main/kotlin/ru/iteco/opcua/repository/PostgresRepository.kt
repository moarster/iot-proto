package ru.iteco.opcua.repository

import org.springframework.data.r2dbc.repository.R2dbcRepository
import org.springframework.stereotype.Repository
import ru.iteco.opcua.model.MeterTimeSeries

@Repository
interface MeterTimeSeriesRepository : R2dbcRepository<MeterTimeSeries, Long>
