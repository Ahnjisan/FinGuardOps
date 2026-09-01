ALTER TABLE audit_log
    DROP CONSTRAINT ck_audit_log_action,
    DROP CONSTRAINT ck_audit_log_reason_code,
    DROP CONSTRAINT ck_audit_log_action_reason,
    DROP CONSTRAINT ck_audit_log_target_context,
    DROP CONSTRAINT ck_audit_log_summary_contract,
    DROP CONSTRAINT ck_audit_log_metadata_keys;

ALTER TABLE audit_log
    ADD CONSTRAINT ck_audit_log_action
        CHECK (
            action IN (
                'CASE_CREATED',
                'CASE_TRANSACTION_LINKED',
                'CASE_STATUS_CHANGED',
                'CASE_ASSIGNEE_CHANGED',
                'CASE_RESOLVED',
                'TRANSACTION_RISK_RESPONSE_APPLIED',
                'TRANSACTION_STATUS_CHANGED'
            )
        ),
    ADD CONSTRAINT ck_audit_log_reason_code
        CHECK (
            reason_code IN (
                'CASE_REQUIRED_BY_RISK_POLICY',
                'CASE_REVIEW_STARTED',
                'CASE_ADDITIONAL_INFORMATION_REQUESTED',
                'CASE_REVIEW_RESUMED',
                'CASE_ASSIGNEE_ASSIGNED',
                'CASE_ASSIGNEE_CHANGED',
                'CASE_ASSIGNEE_RELEASED',
                'CASE_RESOLUTION_COMPLETED',
                'RISK_RESPONSE_DECIDED_BY_POLICY',
                'TRANSACTION_FINALIZED_BY_RISK_POLICY'
            )
        ),
    ADD CONSTRAINT ck_audit_log_action_reason
        CHECK (
            (
                action IN ('CASE_CREATED', 'CASE_TRANSACTION_LINKED')
                AND reason_code = 'CASE_REQUIRED_BY_RISK_POLICY'
            )
            OR (
                action = 'CASE_STATUS_CHANGED'
                AND reason_code IN (
                    'CASE_REVIEW_STARTED',
                    'CASE_ADDITIONAL_INFORMATION_REQUESTED',
                    'CASE_REVIEW_RESUMED'
                )
            )
            OR (
                action = 'CASE_ASSIGNEE_CHANGED'
                AND reason_code IN (
                    'CASE_ASSIGNEE_ASSIGNED',
                    'CASE_ASSIGNEE_CHANGED',
                    'CASE_ASSIGNEE_RELEASED'
                )
            )
            OR (
                action = 'CASE_RESOLVED'
                AND reason_code = 'CASE_RESOLUTION_COMPLETED'
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
    ADD CONSTRAINT ck_audit_log_target_context
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
                    'CASE_STATUS_CHANGED',
                    'CASE_ASSIGNEE_CHANGED',
                    'CASE_RESOLVED'
                )
                AND target_type = 'FRAUD_CASE'
                AND case_id IS NOT NULL
                AND transaction_id IS NULL
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
    ADD CONSTRAINT ck_audit_log_summary_contract
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
            OR (
                action = 'CASE_RESOLVED'
                AND before_value_summary IS NOT NULL
                AND after_value_summary IS NOT NULL
                AND before_value_summary ? 'caseStatus'
                AND before_value_summary ? 'assigneeRef'
                AND before_value_summary
                    - 'caseStatus' - 'assigneeRef' = '{}'::jsonb
                AND jsonb_typeof(
                    before_value_summary -> 'caseStatus'
                ) = 'string'
                AND jsonb_typeof(
                    before_value_summary -> 'assigneeRef'
                ) = 'string'
                AND before_value_summary ->> 'caseStatus' = 'IN_REVIEW'
                AND before_value_summary ->> 'assigneeRef'
                    ~ '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
                AND after_value_summary ? 'caseStatus'
                AND after_value_summary ? 'finalDisposition'
                AND after_value_summary ? 'assigneeRef'
                AND after_value_summary
                    - 'caseStatus'
                    - 'finalDisposition'
                    - 'assigneeRef' = '{}'::jsonb
                AND jsonb_typeof(
                    after_value_summary -> 'caseStatus'
                ) = 'string'
                AND jsonb_typeof(
                    after_value_summary -> 'finalDisposition'
                ) = 'string'
                AND jsonb_typeof(
                    after_value_summary -> 'assigneeRef'
                ) = 'string'
                AND after_value_summary ->> 'caseStatus' = 'CLOSED'
                AND after_value_summary ->> 'finalDisposition' IN (
                    'NORMAL',
                    'FALSE_POSITIVE',
                    'CONFIRMED_FRAUD'
                )
                AND after_value_summary ->> 'assigneeRef'
                    ~ '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
                AND after_value_summary ->> 'assigneeRef'
                    = before_value_summary ->> 'assigneeRef'
            )
            OR (
                action IN (
                    'CASE_STATUS_CHANGED',
                    'CASE_ASSIGNEE_CHANGED',
                    'CASE_RESOLVED'
                )
                AND before_value_summary IS NOT NULL
                AND after_value_summary IS NOT NULL
                AND before_value_summary ? 'caseStatus'
                AND before_value_summary
                    - 'caseStatus' - 'assigneeRef' = '{}'::jsonb
                AND jsonb_typeof(
                    before_value_summary -> 'caseStatus'
                ) = 'string'
                AND before_value_summary ->> 'caseStatus' IN (
                    'OPEN',
                    'IN_REVIEW',
                    'ADDITIONAL_INFORMATION_REQUIRED'
                )
                AND (
                    NOT (before_value_summary ? 'assigneeRef')
                    OR (
                        jsonb_typeof(
                            before_value_summary -> 'assigneeRef'
                        ) = 'string'
                        AND before_value_summary ->> 'assigneeRef'
                            ~ '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
                    )
                )
                AND after_value_summary ? 'caseStatus'
                AND after_value_summary
                    - 'caseStatus' - 'assigneeRef' = '{}'::jsonb
                AND jsonb_typeof(
                    after_value_summary -> 'caseStatus'
                ) = 'string'
                AND after_value_summary ->> 'caseStatus' IN (
                    'OPEN',
                    'IN_REVIEW',
                    'ADDITIONAL_INFORMATION_REQUIRED'
                )
                AND (
                    NOT (after_value_summary ? 'assigneeRef')
                    OR (
                        jsonb_typeof(
                            after_value_summary -> 'assigneeRef'
                        ) = 'string'
                        AND after_value_summary ->> 'assigneeRef'
                            ~ '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
                    )
                )
                AND (
                    (
                        action = 'CASE_STATUS_CHANGED'
                        AND (
                            (
                                reason_code = 'CASE_REVIEW_STARTED'
                                AND before_value_summary
                                    = '{"caseStatus":"OPEN"}'::jsonb
                                AND after_value_summary ->> 'caseStatus'
                                    = 'IN_REVIEW'
                                AND after_value_summary ? 'assigneeRef'
                            )
                            OR (
                                reason_code
                                    = 'CASE_ADDITIONAL_INFORMATION_REQUESTED'
                                AND before_value_summary ->> 'caseStatus'
                                    = 'IN_REVIEW'
                                AND after_value_summary ->> 'caseStatus'
                                    = 'ADDITIONAL_INFORMATION_REQUIRED'
                                AND before_value_summary ? 'assigneeRef'
                                AND after_value_summary ->> 'assigneeRef'
                                    = before_value_summary ->> 'assigneeRef'
                            )
                            OR (
                                reason_code = 'CASE_REVIEW_RESUMED'
                                AND before_value_summary ->> 'caseStatus'
                                    = 'ADDITIONAL_INFORMATION_REQUIRED'
                                AND after_value_summary ->> 'caseStatus'
                                    = 'IN_REVIEW'
                                AND before_value_summary ? 'assigneeRef'
                                AND after_value_summary ->> 'assigneeRef'
                                    = before_value_summary ->> 'assigneeRef'
                            )
                        )
                    )
                    OR (
                        action = 'CASE_ASSIGNEE_CHANGED'
                        AND before_value_summary ->> 'caseStatus'
                            = after_value_summary ->> 'caseStatus'
                        AND before_value_summary ->> 'caseStatus' IN (
                            'IN_REVIEW',
                            'ADDITIONAL_INFORMATION_REQUIRED'
                        )
                        AND (
                            (
                                reason_code = 'CASE_ASSIGNEE_ASSIGNED'
                                AND before_value_summary ->> 'caseStatus'
                                    = 'ADDITIONAL_INFORMATION_REQUIRED'
                                AND NOT (
                                    before_value_summary ? 'assigneeRef'
                                )
                                AND after_value_summary ? 'assigneeRef'
                            )
                            OR (
                                reason_code = 'CASE_ASSIGNEE_CHANGED'
                                AND before_value_summary ? 'assigneeRef'
                                AND after_value_summary ? 'assigneeRef'
                                AND before_value_summary ->> 'assigneeRef'
                                    <> after_value_summary ->> 'assigneeRef'
                            )
                            OR (
                                reason_code = 'CASE_ASSIGNEE_RELEASED'
                                AND before_value_summary ->> 'caseStatus'
                                    = 'ADDITIONAL_INFORMATION_REQUIRED'
                                AND before_value_summary ? 'assigneeRef'
                                AND NOT (
                                    after_value_summary ? 'assigneeRef'
                                )
                            )
                        )
                    )
                )
            )
        ),
    ADD CONSTRAINT ck_audit_log_metadata_keys
        CHECK (
            (
                action IN ('CASE_CREATED', 'CASE_TRANSACTION_LINKED')
                AND metadata
                    - 'detectionResultId'
                    - 'detectionResultVersion' = '{}'::jsonb
            )
            OR (
                action IN (
                    'CASE_STATUS_CHANGED',
                    'CASE_ASSIGNEE_CHANGED',
                    'CASE_RESOLVED'
                )
                AND metadata = '{}'::jsonb
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
        );
