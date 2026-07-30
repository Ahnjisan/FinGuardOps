CREATE TABLE detection_result (
    id BIGINT GENERATED ALWAYS AS IDENTITY,
    detection_result_id UUID NOT NULL,
    financial_transaction_id BIGINT NOT NULL,
    detection_result_version INTEGER NOT NULL,
    analysis_status VARCHAR(16) NOT NULL,
    risk_score INTEGER,
    risk_level VARCHAR(16),
    rule_set_version VARCHAR(64) NOT NULL,
    scoring_policy_version VARCHAR(64) NOT NULL,
    feature_version VARCHAR(64) NOT NULL,
    model_version VARCHAR(64),
    evaluation_cutoff_at TIMESTAMPTZ NOT NULL,
    analysis_started_at TIMESTAMPTZ,
    analysis_completed_at TIMESTAMPTZ,
    failure_code VARCHAR(64),
    analysis_trace_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_detection_result
        PRIMARY KEY (id),
    CONSTRAINT uq_detection_result_business_id
        UNIQUE (detection_result_id),
    CONSTRAINT uq_detection_result_transaction_version
        UNIQUE (financial_transaction_id, detection_result_version),
    CONSTRAINT uq_detection_result_adoption_target
        UNIQUE (id, financial_transaction_id, risk_level),
    CONSTRAINT fk_detection_result_transaction
        FOREIGN KEY (financial_transaction_id)
        REFERENCES financial_transaction (id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_detection_result_uuid_v4
        CHECK (
            get_byte(uuid_send(detection_result_id), 6) BETWEEN 64 AND 79
            AND get_byte(uuid_send(detection_result_id), 8) BETWEEN 128 AND 191
        ),
    CONSTRAINT ck_detection_result_version
        CHECK (detection_result_version > 0),
    CONSTRAINT ck_detection_result_analysis_status
        CHECK (
            analysis_status IN (
                'PENDING',
                'IN_PROGRESS',
                'COMPLETED',
                'FAILED'
            )
        ),
    CONSTRAINT ck_detection_result_risk_score
        CHECK (risk_score IS NULL OR risk_score BETWEEN 0 AND 100),
    CONSTRAINT ck_detection_result_risk_level
        CHECK (
            risk_level IS NULL
            OR risk_level IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')
        ),
    CONSTRAINT ck_detection_result_version_fields
        CHECK (
            char_length(rule_set_version) BETWEEN 1 AND 64
            AND rule_set_version = btrim(rule_set_version)
            AND char_length(scoring_policy_version) BETWEEN 1 AND 64
            AND scoring_policy_version = btrim(scoring_policy_version)
            AND char_length(feature_version) BETWEEN 1 AND 64
            AND feature_version = btrim(feature_version)
            AND (
                model_version IS NULL
                OR (
                    char_length(model_version) BETWEEN 1 AND 64
                    AND model_version = btrim(model_version)
                )
            )
        ),
    CONSTRAINT ck_detection_result_failure_code
        CHECK (
            failure_code IS NULL
            OR failure_code ~ '^[A-Z][A-Z0-9_]{0,63}$'
        ),
    CONSTRAINT ck_detection_result_trace_id
        CHECK (
            char_length(analysis_trace_id) BETWEEN 8 AND 64
            AND analysis_trace_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{7,63}$'
        ),
    CONSTRAINT ck_detection_result_state_fields
        CHECK (
            (
                analysis_status = 'PENDING'
                AND risk_score IS NULL
                AND risk_level IS NULL
                AND analysis_started_at IS NULL
                AND analysis_completed_at IS NULL
                AND failure_code IS NULL
            )
            OR (
                analysis_status = 'IN_PROGRESS'
                AND risk_score IS NULL
                AND risk_level IS NULL
                AND analysis_started_at IS NOT NULL
                AND analysis_completed_at IS NULL
                AND failure_code IS NULL
            )
            OR (
                analysis_status = 'COMPLETED'
                AND risk_score IS NOT NULL
                AND risk_level IS NOT NULL
                AND analysis_started_at IS NOT NULL
                AND analysis_completed_at IS NOT NULL
                AND failure_code IS NULL
            )
            OR (
                analysis_status = 'FAILED'
                AND risk_score IS NULL
                AND risk_level IS NULL
                AND analysis_completed_at IS NOT NULL
                AND failure_code IS NOT NULL
            )
        ),
    CONSTRAINT ck_detection_result_timestamps
        CHECK (
            updated_at >= created_at
            AND (
                analysis_completed_at IS NULL
                OR analysis_started_at IS NULL
                OR analysis_completed_at >= analysis_started_at
            )
        )
);

CREATE TABLE detection_evidence (
    id BIGINT GENERATED ALWAYS AS IDENTITY,
    evidence_id UUID NOT NULL,
    detection_result_id BIGINT NOT NULL,
    evidence_type VARCHAR(32) NOT NULL,
    reason_code VARCHAR(64) NOT NULL,
    display_description VARCHAR(512) NOT NULL,
    score_contribution INTEGER,
    rule_code VARCHAR(64),
    rule_version VARCHAR(32),
    observation_summary JSONB NOT NULL,
    evidence_occurred_at TIMESTAMPTZ NOT NULL,
    sort_order INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_detection_evidence
        PRIMARY KEY (id),
    CONSTRAINT uq_detection_evidence_business_id
        UNIQUE (evidence_id),
    CONSTRAINT uq_detection_evidence_result_sort
        UNIQUE (detection_result_id, sort_order),
    CONSTRAINT fk_detection_evidence_result
        FOREIGN KEY (detection_result_id)
        REFERENCES detection_result (id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_detection_evidence_uuid_v4
        CHECK (
            get_byte(uuid_send(evidence_id), 6) BETWEEN 64 AND 79
            AND get_byte(uuid_send(evidence_id), 8) BETWEEN 128 AND 191
        ),
    CONSTRAINT ck_detection_evidence_type
        CHECK (
            evidence_type IN (
                'RULE',
                'ML',
                'EXTERNAL_RISK',
                'BEHAVIOR_PATTERN'
            )
        ),
    CONSTRAINT ck_detection_evidence_reason_code
        CHECK (reason_code ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    CONSTRAINT ck_detection_evidence_description
        CHECK (
            char_length(display_description) BETWEEN 1 AND 512
            AND display_description = btrim(display_description)
        ),
    CONSTRAINT ck_detection_evidence_score
        CHECK (
            score_contribution IS NULL
            OR score_contribution BETWEEN 0 AND 100
        ),
    CONSTRAINT ck_detection_evidence_rule_fields
        CHECK (
            (
                evidence_type = 'RULE'
                AND rule_code IS NOT NULL
                AND rule_code ~ '^[A-Z][A-Z0-9_]{0,63}$'
                AND rule_version IS NOT NULL
                AND char_length(rule_version) BETWEEN 1 AND 32
                AND rule_version = btrim(rule_version)
                AND score_contribution IS NOT NULL
            )
            OR (
                evidence_type <> 'RULE'
                AND rule_code IS NULL
                AND rule_version IS NULL
            )
        ),
    CONSTRAINT ck_detection_evidence_observation_summary
        CHECK (
            jsonb_typeof(observation_summary) = 'object'
            AND observation_summary <> '{}'::jsonb
        ),
    CONSTRAINT ck_detection_evidence_sort_order
        CHECK (sort_order >= 0)
);

CREATE UNIQUE INDEX uq_detection_evidence_result_rule_code
    ON detection_evidence (detection_result_id, rule_code)
    WHERE evidence_type = 'RULE';

ALTER TABLE financial_transaction
    ADD COLUMN adopted_detection_result_id BIGINT,
    ADD COLUMN risk_level VARCHAR(16),
    ADD COLUMN risk_response_outcome VARCHAR(32);

ALTER TABLE financial_transaction
    ADD CONSTRAINT ck_financial_transaction_risk_level
        CHECK (
            risk_level IS NULL
            OR risk_level IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')
        ),
    ADD CONSTRAINT ck_financial_transaction_risk_response_outcome
        CHECK (
            risk_response_outcome IS NULL
            OR risk_response_outcome IN (
                'APPROVED',
                'APPROVED_WITH_MONITORING',
                'ADDITIONAL_AUTH_REQUIRED',
                'HELD'
            )
        ),
    ADD CONSTRAINT ck_financial_transaction_adopted_risk
        CHECK (
            (
                adopted_detection_result_id IS NULL
                AND risk_level IS NULL
            )
            OR (
                adopted_detection_result_id IS NOT NULL
                AND risk_level IS NOT NULL
            )
        ),
    ADD CONSTRAINT ck_financial_transaction_risk_response_mapping
        CHECK (
            risk_response_outcome IS NULL
            OR (
                adopted_detection_result_id IS NOT NULL
                AND (
                    (risk_level = 'LOW'
                        AND risk_response_outcome = 'APPROVED')
                    OR (risk_level = 'MEDIUM'
                        AND risk_response_outcome =
                            'APPROVED_WITH_MONITORING')
                    OR (risk_level = 'HIGH'
                        AND risk_response_outcome =
                            'ADDITIONAL_AUTH_REQUIRED')
                    OR (risk_level = 'CRITICAL'
                        AND risk_response_outcome = 'HELD')
                )
            )
        ),
    ADD CONSTRAINT fk_financial_transaction_adopted_detection_result
        FOREIGN KEY (
            adopted_detection_result_id,
            id,
            risk_level
        )
        REFERENCES detection_result (
            id,
            financial_transaction_id,
            risk_level
        )
        ON DELETE RESTRICT;

CREATE INDEX ix_detection_result_status_updated_at
    ON detection_result (analysis_status, updated_at, id);

CREATE INDEX ix_financial_transaction_risk_occurred_at
    ON financial_transaction (risk_level, occurred_at DESC, id DESC)
    WHERE risk_level IS NOT NULL;

CREATE FUNCTION guard_detection_result_history()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.analysis_status IN ('COMPLETED', 'FAILED') THEN
            RAISE EXCEPTION
                'terminal detection results are immutable'
                USING ERRCODE = '55000';
        END IF;
        RETURN OLD;
    END IF;

    IF OLD.analysis_status IN ('COMPLETED', 'FAILED') THEN
        RAISE EXCEPTION
            'terminal detection results are immutable'
            USING ERRCODE = '55000';
    END IF;

    IF NEW.id IS DISTINCT FROM OLD.id
        OR NEW.detection_result_id IS DISTINCT FROM OLD.detection_result_id
        OR NEW.financial_transaction_id
            IS DISTINCT FROM OLD.financial_transaction_id
        OR NEW.detection_result_version
            IS DISTINCT FROM OLD.detection_result_version
        OR NEW.rule_set_version IS DISTINCT FROM OLD.rule_set_version
        OR NEW.scoring_policy_version
            IS DISTINCT FROM OLD.scoring_policy_version
        OR NEW.feature_version IS DISTINCT FROM OLD.feature_version
        OR NEW.model_version IS DISTINCT FROM OLD.model_version
        OR NEW.evaluation_cutoff_at
            IS DISTINCT FROM OLD.evaluation_cutoff_at
        OR NEW.analysis_trace_id IS DISTINCT FROM OLD.analysis_trace_id
        OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION
            'immutable detection result fields cannot be changed'
            USING ERRCODE = '55000';
    END IF;

    IF NOT (
        (OLD.analysis_status = 'PENDING'
            AND NEW.analysis_status IN ('IN_PROGRESS', 'FAILED'))
        OR (OLD.analysis_status = 'IN_PROGRESS'
            AND NEW.analysis_status IN ('COMPLETED', 'FAILED'))
    ) THEN
        RAISE EXCEPTION
            'invalid detection result state transition'
            USING ERRCODE = '55000';
    END IF;

    IF NEW.analysis_status = 'FAILED'
        AND EXISTS (
            SELECT 1
            FROM detection_evidence evidence
            WHERE evidence.detection_result_id = OLD.id
        ) THEN
        RAISE EXCEPTION
            'failed detection results cannot retain evidence'
            USING ERRCODE = '55000';
    END IF;

    RETURN NEW;
END
$$;

CREATE TRIGGER tg_detection_result_history_guard
BEFORE UPDATE OR DELETE ON detection_result
FOR EACH ROW
EXECUTE FUNCTION guard_detection_result_history();

CREATE FUNCTION guard_detection_evidence_history()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    parent_status VARCHAR(16);
BEGIN
    IF TG_OP IN ('UPDATE', 'DELETE') THEN
        RAISE EXCEPTION
            'detection evidence is immutable'
            USING ERRCODE = '55000';
    END IF;

    SELECT result.analysis_status
    INTO parent_status
    FROM detection_result result
    WHERE result.id = NEW.detection_result_id
    FOR SHARE;

    IF parent_status IS DISTINCT FROM 'IN_PROGRESS' THEN
        RAISE EXCEPTION
            'evidence can only be added to an in-progress result'
            USING ERRCODE = '55000';
    END IF;

    RETURN NEW;
END
$$;

CREATE TRIGGER tg_detection_evidence_history_guard
BEFORE INSERT OR UPDATE OR DELETE ON detection_evidence
FOR EACH ROW
EXECUTE FUNCTION guard_detection_evidence_history();

CREATE FUNCTION validate_financial_transaction_adoption()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    adopted_status VARCHAR(16);
BEGIN
    IF NEW.adopted_detection_result_id IS NULL THEN
        RETURN NEW;
    END IF;

    SELECT result.analysis_status
    INTO adopted_status
    FROM detection_result result
    WHERE result.id = NEW.adopted_detection_result_id;

    IF adopted_status IS DISTINCT FROM 'COMPLETED' THEN
        RAISE EXCEPTION
            'ck_financial_transaction_adopted_result_completed: only completed detection results can be adopted'
            USING ERRCODE = '23514',
                CONSTRAINT =
                    'ck_financial_transaction_adopted_result_completed';
    END IF;

    RETURN NEW;
END
$$;

CREATE TRIGGER tg_financial_transaction_adoption_guard
BEFORE INSERT OR UPDATE OF
    adopted_detection_result_id,
    risk_level,
    risk_response_outcome
ON financial_transaction
FOR EACH ROW
EXECUTE FUNCTION validate_financial_transaction_adoption();
