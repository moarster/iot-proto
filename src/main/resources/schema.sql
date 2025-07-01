-- PostgreSQL TimeSeries table
CREATE EXTENSION IF NOT EXISTS timescaledb;

CREATE TABLE IF NOT EXISTS meter_timeseries (
    id BIGSERIAL,
    uspd_id UUID NOT NULL,
    meter_id UUID NOT NULL,
    subsystem VARCHAR(1) NOT NULL,
    meter_type VARCHAR(4) NOT NULL,
    property  VARCHAR(20) NOT NULL,
    node_id TEXT NOT NULL,
    value DOUBLE PRECISION NOT NULL,
    unit VARCHAR(20),
    timestamp TIMESTAMP NOT NULL,
    server_timestamp TIMESTAMP NOT NULL,
    PRIMARY KEY (id, timestamp)
);
SELECT create_hypertable('meter_timeseries', 'timestamp', if_not_exists => TRUE);

-- Create index for time-based queries
CREATE INDEX IF NOT EXISTS idx_meter_timeseries_timestamp ON meter_timeseries(timestamp);
CREATE INDEX IF NOT EXISTS idx_meter_timeseries_meter_time ON meter_timeseries(uspd_id,meter_id, timestamp);
CREATE INDEX IF NOT EXISTS idx_meter_timeseries_property ON meter_timeseries(uspd_id,meter_id, property);

ALTER TABLE meter_timeseries SET (timescaledb.compress, timescaledb.compress_orderby = 'timestamp DESC');
SELECT add_compression_policy('meter_timeseries', INTERVAL '90 days', if_not_exists => TRUE);