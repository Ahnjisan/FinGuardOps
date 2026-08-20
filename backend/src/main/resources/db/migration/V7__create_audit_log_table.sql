CREATE TABLE audit_log (
    id BIGINT GENERATED ALWAYS AS IDENTITY,
    audit_id UUID NOT NULL,
    actor_type VARCHAR(16) NOT NULL,
    actor_id VARCHAR(128) NOT NULL,
    action VARCHAR(64) NOT NULL,
    reason_code VARCHAR(64) NOT NULL,
    target_type VARCHAR(32) NOT NULL,
    target_id UUID NOT NULL,
    transaction_id UUID,
    case_id UUID,
    trace_id VARCHAR(64),
    before_value_summary JSONB,
    after_value_summary JSONB,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    changed_at TIMESTAMPTZ(6) NOT NULL,

    CONSTRAINT pk_audit_log
        PRIMARY KEY (id),
    CONSTRAINT uq_audit_log_audit_id
        UNIQUE (audit_id),
    CONSTRAINT ck_audit_log_audit_id_uuid_v4
        CHECK (
            get_byte(uuid_send(audit_id), 6) BETWEEN 64 AND 79
            AND get_byte(uuid_send(audit_id), 8) BETWEEN 128 AND 191
        ),
    CONSTRAINT ck_audit_log_actor_type
        CHECK (actor_type IN ('SYSTEM', 'USER')),
    CONSTRAINT ck_audit_log_actor
        CHECK (
            (
                actor_type = 'SYSTEM'
                AND actor_id = 'finguardops-backend'
            )
            OR (
                actor_type = 'USER'
                AND actor_id
                    ~ '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
            )
        ),
    CONSTRAINT ck_audit_log_action
        CHECK (
            action IN (
                'CASE_CREATED',
                'CASE_TRANSACTION_LINKED',
                'TRANSACTION_RISK_RESPONSE_APPLIED',
                'TRANSACTION_STATUS_CHANGED'
            )
        ),
    CONSTRAINT ck_audit_log_reason_code
        CHECK (
            reason_code IN (
                'CASE_REQUIRED_BY_RISK_POLICY',
                'RISK_RESPONSE_DECIDED_BY_POLICY',
                'TRANSACTION_FINALIZED_BY_RISK_POLICY'
            )
        ),
    CONSTRAINT ck_audit_log_action_reason
        CHECK (
            (
                action IN ('CASE_CREATED', 'CASE_TRANSACTION_LINKED')
                AND reason_code = 'CASE_REQUIRED_BY_RISK_POLICY'
            )
            OR (
                action = 'TRANSACTION_RISK_RESPONSE_APPLIED'
                AND reason_code = 'RISK_RESPONSE_DECIDED_BY_POLICY'
            )
            OR (
                action = 'TRANSACTION_STATUS_CHANGED'
                AND reason_code = 'TRANSACTION_FINALIZED_BY_RISK_POLICY'
            )
        ),
    CONSTRAINT ck_audit_log_target_type
        CHECK (
            target_type IN ('FINANCIAL_TRANSACTION', 'FRAUD_CASE')
        ),
    CONSTRAINT ck_audit_log_target_id_uuid_v4
        CHECK (
            get_byte(uuid_send(target_id), 6) BETWEEN 64 AND 79
            AND get_byte(uuid_send(target_id), 8) BETWEEN 128 AND 191
        ),
    CONSTRAINT ck_audit_log_target_context
        CHECK (
            (
                action IN ('CASE_CREATED', 'CASE_TRANSACTION_LINKED')
                AND target_type = 'FRAUD_CASE'
                AND case_id IS NOT NULL
                AND transaction_id IS NOT NULL
                AND target_id = case_id
            )
            OR (
                action IN (
                    'TRANSACTION_RISK_RESPONSE_APPLIED',
                    'TRANSACTION_STATUS_CHANGED'
                )
                AND target_type = 'FINANCIAL_TRANSACTION'
                AND transaction_id IS NOT NULL
                AND target_id = transaction_id
            )
        ),
    CONSTRAINT ck_audit_log_trace_id
        CHECK (
            trace_id IS NULL
            OR trace_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{7,63}$'
        ),
    CONSTRAINT ck_audit_log_json_objects
        CHECK (
            (
                before_value_summary IS NULL
                OR jsonb_typeof(before_value_summary) = 'object'
            )
            AND (
                after_value_summary IS NULL
                OR jsonb_typeof(after_value_summary) = 'object'
            )
            AND jsonb_typeof(metadata) = 'object'
        ),
    CONSTRAINT ck_audit_log_json_size
        CHECK (
            COALESCE(octet_length(before_value_summary::text), 0)
            + COALESCE(octet_length(after_value_summary::text), 0)
            + octet_length(metadata::text)
            <= 8192
        ),
    CONSTRAINT ck_audit_log_summary_contract
        CHECK (
            (
                action = 'CASE_CREATED'
                AND before_value_summary IS NULL
                AND after_value_summary IS NOT NULL
                AND after_value_summary = '{"caseStatus":"OPEN"}'::jsonb
            )
            OR (
                action = 'CASE_TRANSACTION_LINKED'
                AND before_value_summary IS NULL
                AND after_value_summary IS NOT NULL
                AND after_value_summary = '{"linked":true}'::jsonb
            )
            OR (
                action = 'TRANSACTION_RISK_RESPONSE_APPLIED'
                AND after_value_summary IS NOT NULL
                AND (
                    before_value_summary IS NULL
                    OR (
                        before_value_summary ? 'riskResponseOutcome'
                        AND before_value_summary
                            - 'riskResponseOutcome' = '{}'::jsonb
                        AND jsonb_typeof(
                            before_value_summary -> 'riskResponseOutcome'
                        ) = 'string'
                        AND before_value_summary ->> 'riskResponseOutcome'
                            IN (
                                'APPROVED',
                                'APPROVED_WITH_MONITORING',
                                'ADDITIONAL_AUTH_REQUIRED',
                                'HELD'
                            )
                    )
                )
                AND after_value_summary ? 'riskResponseOutcome'
                AND after_value_summary
                    - 'riskResponseOutcome' = '{}'::jsonb
                AND jsonb_typeof(
                    after_value_summary -> 'riskResponseOutcome'
                ) = 'string'
                AND after_value_summary ->> 'riskResponseOutcome'
                    IN (
                        'APPROVED',
                        'APPROVED_WITH_MONITORING',
                        'ADDITIONAL_AUTH_REQUIRED',
                        'HELD'
                    )
            )
            OR (
                action = 'TRANSACTION_STATUS_CHANGED'
                AND before_value_summary IS NOT NULL
                AND after_value_summary IS NOT NULL
                AND before_value_summary ? 'processingStatus'
                AND before_value_summary
                    - 'processingStatus' = '{}'::jsonb
                AND jsonb_typeof(
                    before_value_summary -> 'processingStatus'
                ) = 'string'
                AND before_value_summary ->> 'processingStatus'
                    IN (
                        'RECEIVED',
                        'ANALYZING',
                        'ANALYZED',
                        'APPROVED',
                        'ADDITIONAL_AUTH_REQUIRED',
                        'HELD',
                        'FAILED'
                    )
                AND after_value_summary ? 'processingStatus'
                AND after_value_summary
                    - 'processingStatus' = '{}'::jsonb
                AND jsonb_typeof(
                    after_value_summary -> 'processingStatus'
                ) = 'string'
                AND after_value_summary ->> 'processingStatus'
                    IN (
                        'RECEIVED',
                        'ANALYZING',
                        'ANALYZED',
                        'APPROVED',
                        'ADDITIONAL_AUTH_REQUIRED',
                        'HELD',
                        'FAILED'
                    )
            )
        ),
    CONSTRAINT ck_audit_log_metadata_keys
        CHECK (
            (
                action IN ('CASE_CREATED', 'CASE_TRANSACTION_LINKED')
                AND metadata
                    - 'detectionResultId'
                    - 'detectionResultVersion' = '{}'::jsonb
            )
            OR (
                action IN (
                    'TRANSACTION_RISK_RESPONSE_APPLIED',
                    'TRANSACTION_STATUS_CHANGED'
                )
                AND metadata
                    - 'sourceRiskLevel'
                    - 'detectionResultId'
                    - 'detectionResultVersion' = '{}'::jsonb
            )
        ),
    CONSTRAINT ck_audit_log_metadata_values
        CHECK (
            (
                NOT (metadata ? 'detectionResultId')
                OR (
                    jsonb_typeof(metadata -> 'detectionResultId') = 'string'
                    AND metadata ->> 'detectionResultId'
                        ~ '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
                )
            )
            AND (
                NOT (metadata ? 'detectionResultVersion')
                OR (
                    jsonb_typeof(
                        metadata -> 'detectionResultVersion'
                    ) = 'number'
                    AND metadata ->> 'detectionResultVersion'
                        ~ '^[1-9][0-9]{0,9}$'
                    AND (metadata ->> 'detectionResultVersion')::BIGINT
                        <= 2147483647
                )
            )
            AND (
                NOT (metadata ? 'sourceRiskLevel')
                OR (
                    jsonb_typeof(metadata -> 'sourceRiskLevel') = 'string'
                    AND metadata ->> 'sourceRiskLevel'
                        IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')
                )
            )
        ),
    CONSTRAINT fk_audit_log_transaction
        FOREIGN KEY (transaction_id)
        REFERENCES financial_transaction (transaction_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_audit_log_case
        FOREIGN KEY (case_id)
        REFERENCES fraud_case (case_id)
        ON DELETE RESTRICT
);

CREATE INDEX ix_audit_log_case_changed
    ON audit_log (case_id, changed_at DESC, id DESC)
    WHERE case_id IS NOT NULL;

CREATE INDEX ix_audit_log_transaction_changed
    ON audit_log (transaction_id, changed_at DESC, id DESC)
    WHERE transaction_id IS NOT NULL;

CREATE INDEX ix_audit_log_target_changed
    ON audit_log (target_type, target_id, changed_at DESC, id DESC);

CREATE INDEX ix_audit_log_trace_changed
    ON audit_log (trace_id, changed_at DESC)
    WHERE trace_id IS NOT NULL;

CREATE FUNCTION reject_audit_log_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'audit_log is append-only'
        USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER tr_audit_log_reject_mutation
    BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW
    EXECUTE FUNCTION reject_audit_log_mutation();
