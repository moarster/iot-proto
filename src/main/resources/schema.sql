-- PostgreSQL TimeSeries table
CREATE EXTENSION IF NOT EXISTS timescaledb;

CREATE TABLE IF NOT EXISTS meter_timeseries (
    id BIGSERIAL PRIMARY KEY,
    meter_id VARCHAR(50) NOT NULL,
    meter_type VARCHAR(50) NOT NULL,
    value DOUBLE PRECISION NOT NULL,
    unit VARCHAR(20) NOT NULL,
    timestamp TIMESTAMP NOT NULL
);
SELECT create_hypertable('meter_timeseries', 'timestamp', if_not_exists => TRUE);

-- Create index for time-based queries
CREATE INDEX IF NOT EXISTS idx_meter_timeseries_timestamp ON meter_timeseries(timestamp);
CREATE INDEX IF NOT EXISTS idx_meter_timeseries_meter_time ON meter_timeseries(meter_id, timestamp);

ALTER TABLE meter_timeseries SET (timescaledb.compress, timescaledb.compress_orderby = 'timestamp DESC');
SELECT add_compression_policy('meter_timeseries', INTERVAL '90 days');