CREATE TABLE scenario_case_correlation (
    fraud_case_id BIGINT PRIMARY KEY REFERENCES fraud_case(id),
    correlation_key CHAR(64) NOT NULL CHECK (correlation_key ~ '^[0-9a-f]{64}$'),
    scenario_code VARCHAR(16) NOT NULL CHECK (scenario_code IN ('SCN001','SCN002')),
    anchor_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

CREATE INDEX ix_scenario_case_correlation_lookup
    ON scenario_case_correlation (correlation_key, anchor_at, fraud_case_id);
