ALTER TABLE detection_result
    ADD COLUMN ml_feature_version VARCHAR(64),
    ADD COLUMN model_sha256 VARCHAR(64),
    ADD COLUMN rule_risk_score INTEGER,
    ADD COLUMN ml_contribution INTEGER,
    ADD COLUMN ml_probability_basis_points INTEGER;

ALTER TABLE detection_result
    ADD CONSTRAINT ck_detection_result_ml_identity CHECK (
        (scoring_policy_version <> 'rule-ml-policy-v1'
            AND ml_feature_version IS NULL AND model_sha256 IS NULL)
        OR (scoring_policy_version = 'rule-ml-policy-v1' AND model_version IS NOT NULL
            AND ml_feature_version = 'fraud-feature-v1'
            AND model_sha256 ~ '^[0-9a-f]{64}$')
    ),
    ADD CONSTRAINT ck_detection_result_ml_scores CHECK (
        (rule_risk_score IS NULL AND ml_contribution IS NULL
            AND ml_probability_basis_points IS NULL
            AND (scoring_policy_version <> 'rule-ml-policy-v1'
                OR analysis_status <> 'COMPLETED'))
        OR (analysis_status = 'COMPLETED' AND model_version IS NOT NULL
            AND rule_risk_score BETWEEN 0 AND 100
            AND ml_contribution BETWEEN 0 AND 40
            AND ml_probability_basis_points BETWEEN 0 AND 10000
            AND risk_score = LEAST(100, rule_risk_score + ml_contribution)
            AND risk_level = CASE
                WHEN risk_score < 20 THEN 'LOW'
                WHEN risk_score < 50 THEN 'MEDIUM'
                WHEN risk_score < 80 THEN 'HIGH'
                ELSE 'CRITICAL'
            END
            AND ml_contribution = CASE
                WHEN ml_probability_basis_points <= 5000 THEN 0
                ELSE ((ml_probability_basis_points - 5000) * 40 + 2500) / 5000
            END)
    );

CREATE OR REPLACE FUNCTION guard_detection_result_history()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.analysis_status IN ('COMPLETED', 'FAILED') THEN
            RAISE EXCEPTION 'terminal detection results are immutable' USING ERRCODE = '55000';
        END IF;
        RETURN OLD;
    END IF;
    IF OLD.analysis_status IN ('COMPLETED', 'FAILED') THEN
        RAISE EXCEPTION 'terminal detection results are immutable' USING ERRCODE = '55000';
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id
        OR NEW.detection_result_id IS DISTINCT FROM OLD.detection_result_id
        OR NEW.financial_transaction_id IS DISTINCT FROM OLD.financial_transaction_id
        OR NEW.detection_result_version IS DISTINCT FROM OLD.detection_result_version
        OR NEW.rule_set_version IS DISTINCT FROM OLD.rule_set_version
        OR NEW.scoring_policy_version IS DISTINCT FROM OLD.scoring_policy_version
        OR NEW.feature_version IS DISTINCT FROM OLD.feature_version
        OR NEW.model_version IS DISTINCT FROM OLD.model_version
        OR NEW.ml_feature_version IS DISTINCT FROM OLD.ml_feature_version
        OR NEW.model_sha256 IS DISTINCT FROM OLD.model_sha256
        OR NEW.evaluation_cutoff_at IS DISTINCT FROM OLD.evaluation_cutoff_at
        OR NEW.analysis_trace_id IS DISTINCT FROM OLD.analysis_trace_id
        OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'immutable detection result fields cannot be changed' USING ERRCODE = '55000';
    END IF;
    IF NOT ((OLD.analysis_status = 'PENDING' AND NEW.analysis_status IN ('IN_PROGRESS', 'FAILED'))
        OR (OLD.analysis_status = 'IN_PROGRESS' AND NEW.analysis_status IN ('COMPLETED', 'FAILED'))) THEN
        RAISE EXCEPTION 'invalid detection result state transition' USING ERRCODE = '55000';
    END IF;
    IF NEW.analysis_status = 'FAILED' AND EXISTS (
        SELECT 1 FROM detection_evidence evidence WHERE evidence.detection_result_id = OLD.id
    ) THEN
        RAISE EXCEPTION 'failed detection results cannot retain evidence' USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END $$;
