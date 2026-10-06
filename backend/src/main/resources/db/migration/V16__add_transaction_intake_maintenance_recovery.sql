CREATE TABLE transaction_intake_maintenance_gate (
    id SMALLINT PRIMARY KEY CHECK (id = 1),
    closed BOOLEAN NOT NULL,
    changed_at TIMESTAMPTZ(6) NOT NULL
);

INSERT INTO transaction_intake_maintenance_gate (id, closed, changed_at)
VALUES (1, false, clock_timestamp());

ALTER TABLE idempotency_recovery_audit_log
    DROP CONSTRAINT ck_idempotency_recovery_decision,
    DROP CONSTRAINT ck_idempotency_recovery_audit_result,
    DROP CONSTRAINT ck_idempotency_recovery_decision_result;

ALTER TABLE idempotency_recovery_audit_log
    ADD CONSTRAINT ck_idempotency_recovery_decision CHECK (
        recovery_decision IN (
            'RECOVERABLE_COMPLETION_GAP', 'ANALYZED_FINALIZED',
            'UNLINKED_CLAIM_TERMINATED', 'RECEIVED_TERMINATED',
            'ANALYZING_TERMINATED', 'CONFIRMED_FAILURE_TERMINATED',
            'MAINTENANCE_PRECONDITION_FAILED', 'LOCK_CONTENTION',
            'MISSING_IDEMPOTENCY_RECORD', 'MISSING_TRANSACTION',
            'PROCESSING_INDETERMINATE', 'FINALIZATION_INCOMPLETE',
            'CONFIRMED_DOMAIN_FAILURE', 'INCONSISTENT_FINAL_STATE',
            'INCONSISTENT_CASE_RELATIONSHIP', 'FINALIZATION_AUDIT_MISMATCH',
            'CONFLICTING_IDEMPOTENCY_DATA', 'ALREADY_TERMINAL',
            'INTERNAL_FAILURE'
        )
    ),
    ADD CONSTRAINT ck_idempotency_recovery_audit_result CHECK (
        audit_result IN ('RECOVERED', 'TERMINATED', 'REJECTED', 'FAILED')
    ),
    ADD CONSTRAINT ck_idempotency_recovery_decision_result CHECK (
        (recovery_decision IN ('RECOVERABLE_COMPLETION_GAP',
            'ANALYZED_FINALIZED') AND audit_result = 'RECOVERED')
        OR (recovery_decision IN ('UNLINKED_CLAIM_TERMINATED',
            'RECEIVED_TERMINATED', 'ANALYZING_TERMINATED',
            'CONFIRMED_FAILURE_TERMINATED') AND audit_result = 'TERMINATED')
        OR (recovery_decision = 'INTERNAL_FAILURE'
            AND audit_result = 'FAILED')
        OR (recovery_decision NOT IN ('RECOVERABLE_COMPLETION_GAP',
            'ANALYZED_FINALIZED', 'UNLINKED_CLAIM_TERMINATED',
            'RECEIVED_TERMINATED', 'ANALYZING_TERMINATED',
            'CONFIRMED_FAILURE_TERMINATED', 'INTERNAL_FAILURE')
            AND audit_result = 'REJECTED')
    );

ALTER TABLE audit_log
    DROP CONSTRAINT ck_audit_log_reason_code,
    DROP CONSTRAINT ck_audit_log_action_reason;

ALTER TABLE audit_log
    ADD CONSTRAINT ck_audit_log_reason_code CHECK (reason_code IN (
        'CASE_REQUIRED_BY_RISK_POLICY', 'CASE_REVIEW_STARTED',
        'CASE_ADDITIONAL_INFORMATION_REQUESTED', 'CASE_REVIEW_RESUMED',
        'CASE_ASSIGNEE_ASSIGNED', 'CASE_ASSIGNEE_CHANGED',
        'CASE_ASSIGNEE_RELEASED', 'CASE_RESOLUTION_COMPLETED',
        'CASE_INVESTIGATION_NOTE_ADDED', 'RISK_RESPONSE_DECIDED_BY_POLICY',
        'TRANSACTION_FINALIZED_BY_RISK_POLICY',
        'TRANSACTION_TERMINATED_BY_MAINTENANCE'
    )),
    ADD CONSTRAINT ck_audit_log_action_reason CHECK (
        (action IN ('CASE_CREATED', 'CASE_TRANSACTION_LINKED')
            AND reason_code = 'CASE_REQUIRED_BY_RISK_POLICY')
        OR (action = 'CASE_STATUS_CHANGED' AND reason_code IN (
            'CASE_REVIEW_STARTED', 'CASE_ADDITIONAL_INFORMATION_REQUESTED',
            'CASE_REVIEW_RESUMED'))
        OR (action = 'CASE_ASSIGNEE_CHANGED' AND reason_code IN (
            'CASE_ASSIGNEE_ASSIGNED', 'CASE_ASSIGNEE_CHANGED',
            'CASE_ASSIGNEE_RELEASED'))
        OR (action = 'CASE_RESOLVED'
            AND reason_code = 'CASE_RESOLUTION_COMPLETED')
        OR (action = 'CASE_NOTE_CREATED'
            AND reason_code = 'CASE_INVESTIGATION_NOTE_ADDED')
        OR (action = 'TRANSACTION_RISK_RESPONSE_APPLIED'
            AND reason_code = 'RISK_RESPONSE_DECIDED_BY_POLICY')
        OR (action = 'TRANSACTION_STATUS_CHANGED'
            AND reason_code IN ('TRANSACTION_FINALIZED_BY_RISK_POLICY',
                'TRANSACTION_TERMINATED_BY_MAINTENANCE'))
    );
