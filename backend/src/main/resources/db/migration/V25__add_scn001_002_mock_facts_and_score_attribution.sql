-- Local, authenticated Mock assertions. The transaction intake payload never owns
-- these observations. No raw IP, password or provider response is persisted here.
CREATE TABLE fds_mock_fact (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    fact_id UUID NOT NULL UNIQUE,
    fact_type VARCHAR(40) NOT NULL,
    source_code VARCHAR(32) NOT NULL DEFAULT 'LOCAL_FDS_MOCK',
    source_version VARCHAR(16) NOT NULL DEFAULT 'v1',
    external_customer_ref VARCHAR(128) NOT NULL,
    account_ref VARCHAR(128),
    device_ref VARCHAR(128),
    beneficiary_ref VARCHAR(128),
    behavior_event_id UUID,
    occurred_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    coverage_from TIMESTAMPTZ,
    coverage_through TIMESTAMPTZ,
    assertion_state VARCHAR(32),
    limit_scope VARCHAR(16),
    previous_limit NUMERIC(19,4),
    new_limit NUMERIC(19,4),
    country_code VARCHAR(2),
    request_fingerprint VARCHAR(64) NOT NULL,
    CONSTRAINT ck_fds_mock_fact_type CHECK (fact_type IN (
        'TRANSFER_HISTORY_COMPLETE', 'DEVICE_HISTORY_COMPLETE',
        'DEVICE_TRUST', 'LIMIT_CHANGE', 'IDENTITY_CHECK',
        'APP_SECURITY', 'NETWORK_REGION')),
    CONSTRAINT ck_fds_mock_fact_source CHECK (
        source_code = 'LOCAL_FDS_MOCK' AND source_version = 'v1'),
    CONSTRAINT ck_fds_mock_fact_refs CHECK (
        char_length(external_customer_ref) BETWEEN 1 AND 128
        AND external_customer_ref = btrim(external_customer_ref)
        AND (account_ref IS NULL OR (char_length(account_ref) BETWEEN 1 AND 128
            AND account_ref = btrim(account_ref)))
        AND (device_ref IS NULL OR (char_length(device_ref) BETWEEN 1 AND 128
            AND device_ref = btrim(device_ref)))
        AND (beneficiary_ref IS NULL OR (char_length(beneficiary_ref) BETWEEN 1 AND 128
            AND beneficiary_ref = btrim(beneficiary_ref)))),
    CONSTRAINT ck_fds_mock_fact_time CHECK (
        occurred_at <= created_at + INTERVAL '5 minutes'
        AND (coverage_from IS NULL OR coverage_from <= occurred_at)
        AND (coverage_through IS NULL OR coverage_through >= occurred_at)),
    CONSTRAINT ck_fds_mock_fact_limit CHECK (
        (fact_type = 'LIMIT_CHANGE' AND account_ref IS NOT NULL
            AND behavior_event_id IS NOT NULL
            AND limit_scope IN ('PER_TRANSFER', 'DAILY')
            AND previous_limit > 0 AND new_limit > 0
            AND previous_limit = trunc(previous_limit)
            AND new_limit = trunc(new_limit)
            AND assertion_state IN ('SUCCEEDED', 'FAILED'))
        OR (fact_type <> 'LIMIT_CHANGE' AND limit_scope IS NULL
            AND previous_limit IS NULL AND new_limit IS NULL)),
    CONSTRAINT ck_fds_mock_fact_fingerprint CHECK (
        request_fingerprint ~ '^[0-9a-f]{64}$')
);

-- A fact cannot be replaced under a fresh UUID at the same owned subject/time.
CREATE UNIQUE INDEX uq_fds_mock_fact_semantic
    ON fds_mock_fact (fact_type, external_customer_ref,
        COALESCE(account_ref, ''), COALESCE(device_ref, ''),
        COALESCE(beneficiary_ref, ''),
        COALESCE(behavior_event_id::text, ''), occurred_at);
CREATE INDEX ix_fds_mock_fact_cutoff
    ON fds_mock_fact (external_customer_ref, fact_type, occurred_at DESC,
        created_at DESC, fact_id ASC);

ALTER TABLE detection_evidence
    ADD COLUMN applied_contribution INTEGER,
    ADD CONSTRAINT ck_detection_evidence_applied_contribution CHECK (
        applied_contribution IS NULL OR applied_contribution BETWEEN 0 AND 100);
ALTER TABLE detection_result
    ADD COLUMN score_group_summary JSONB;

ALTER TABLE detection_result
    DROP CONSTRAINT ck_detection_result_ml_identity,
    DROP CONSTRAINT ck_detection_result_ml_scores;
ALTER TABLE detection_result
    ADD CONSTRAINT ck_detection_result_ml_identity CHECK (
        (scoring_policy_version NOT IN (
            'rule-ml-policy-v1', 'rule-ml-policy-v2', 'rule-ml-policy-v3')
            AND ml_feature_version IS NULL AND model_sha256 IS NULL)
        OR (scoring_policy_version IN ('rule-ml-policy-v1', 'rule-ml-policy-v2')
            AND model_version IS NOT NULL AND ml_feature_version = 'fraud-feature-v1'
            AND model_sha256 ~ '^[0-9a-f]{64}$')
        OR (scoring_policy_version = 'rule-ml-policy-v3'
            AND model_version = 'fraud-logistic-v3'
            AND ml_feature_version = 'fraud-feature-v2'
            AND model_sha256 ~ '^[0-9a-f]{64}$')
    ),
    ADD CONSTRAINT ck_detection_result_ml_scores CHECK (
        (scoring_policy_version NOT IN (
            'rule-ml-policy-v1', 'rule-ml-policy-v2', 'rule-ml-policy-v3')
            AND rule_risk_score IS NULL AND ml_contribution IS NULL
            AND ml_probability_basis_points IS NULL)
        OR (scoring_policy_version IN (
            'rule-ml-policy-v1', 'rule-ml-policy-v2', 'rule-ml-policy-v3')
            AND analysis_status <> 'COMPLETED'
            AND rule_risk_score IS NULL AND ml_contribution IS NULL
            AND ml_probability_basis_points IS NULL)
        OR (scoring_policy_version IN (
            'rule-ml-policy-v1', 'rule-ml-policy-v2', 'rule-ml-policy-v3')
            AND analysis_status = 'COMPLETED'
            AND rule_risk_score BETWEEN 0 AND 100
            AND ml_contribution BETWEEN 0 AND 40
            AND ml_probability_basis_points BETWEEN 0 AND 10000
            AND risk_score = LEAST(100, rule_risk_score + ml_contribution)
            AND risk_level = CASE WHEN risk_score < 20 THEN 'LOW'
                WHEN risk_score < 50 THEN 'MEDIUM'
                WHEN risk_score < 80 THEN 'HIGH' ELSE 'CRITICAL' END
            AND ml_contribution = CASE
                WHEN ml_probability_basis_points <= 5000 THEN 0
                ELSE ((ml_probability_basis_points - 5000) * 40 + 2500) / 5000 END)
    );
