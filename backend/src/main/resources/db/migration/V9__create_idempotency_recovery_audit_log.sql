CREATE TABLE idempotency_recovery_audit_log (
    id BIGINT GENERATED ALWAYS AS IDENTITY,
    audit_id UUID NOT NULL,
    idempotency_record_id BIGINT NOT NULL,
    transaction_id UUID,
    actor_type VARCHAR(16) NOT NULL,
    actor_id VARCHAR(128) NOT NULL,
    recovery_decision VARCHAR(48) NOT NULL,
    audit_result VARCHAR(16) NOT NULL,
    attempted_at TIMESTAMPTZ(6) NOT NULL,

    CONSTRAINT pk_idempotency_recovery_audit_log
        PRIMARY KEY (id),
    CONSTRAINT uq_idempotency_recovery_audit_id
        UNIQUE (audit_id),
    CONSTRAINT ck_idempotency_recovery_audit_id_uuid_v4
        CHECK (
            get_byte(uuid_send(audit_id), 6) BETWEEN 64 AND 79
            AND get_byte(uuid_send(audit_id), 8) BETWEEN 128 AND 191
        ),
    CONSTRAINT ck_idempotency_recovery_record_id
        CHECK (idempotency_record_id > 0),
    CONSTRAINT ck_idempotency_recovery_transaction_uuid_v4
        CHECK (
            transaction_id IS NULL
            OR (
                get_byte(uuid_send(transaction_id), 6) BETWEEN 64 AND 79
                AND get_byte(uuid_send(transaction_id), 8) BETWEEN 128 AND 191
            )
        ),
    CONSTRAINT ck_idempotency_recovery_actor_type
        CHECK (actor_type IN ('SYSTEM', 'USER')),
    CONSTRAINT ck_idempotency_recovery_actor
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
    CONSTRAINT ck_idempotency_recovery_decision
        CHECK (
            recovery_decision IN (
                'RECOVERABLE_COMPLETION_GAP',
                'MISSING_IDEMPOTENCY_RECORD',
                'MISSING_TRANSACTION',
                'PROCESSING_INDETERMINATE',
                'FINALIZATION_INCOMPLETE',
                'CONFIRMED_DOMAIN_FAILURE',
                'INCONSISTENT_FINAL_STATE',
                'INCONSISTENT_CASE_RELATIONSHIP',
                'FINALIZATION_AUDIT_MISMATCH',
                'CONFLICTING_IDEMPOTENCY_DATA',
                'ALREADY_TERMINAL',
                'INTERNAL_FAILURE'
            )
        ),
    CONSTRAINT ck_idempotency_recovery_audit_result
        CHECK (audit_result IN ('RECOVERED', 'REJECTED', 'FAILED')),
    CONSTRAINT ck_idempotency_recovery_decision_result
        CHECK (
            (
                recovery_decision = 'RECOVERABLE_COMPLETION_GAP'
                AND audit_result = 'RECOVERED'
            )
            OR (
                recovery_decision = 'INTERNAL_FAILURE'
                AND audit_result = 'FAILED'
            )
            OR (
                recovery_decision NOT IN (
                    'RECOVERABLE_COMPLETION_GAP',
                    'INTERNAL_FAILURE'
                )
                AND audit_result = 'REJECTED'
            )
        )
);

CREATE INDEX ix_idempotency_recovery_audit_record_attempted
    ON idempotency_recovery_audit_log (
        idempotency_record_id,
        attempted_at DESC,
        id DESC
    );

CREATE INDEX ix_idempotency_record_recovery_candidates
    ON idempotency_record (operation_scope, updated_at, id)
    WHERE processing_status = 'IN_PROGRESS';
