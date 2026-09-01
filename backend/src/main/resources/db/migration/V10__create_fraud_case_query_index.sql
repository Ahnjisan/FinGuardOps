CREATE INDEX ix_fraud_case_last_changed
    ON fraud_case (last_changed_at, id);
