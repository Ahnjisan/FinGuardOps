ALTER TABLE idempotency_record
    DROP CONSTRAINT IF EXISTS ck_idempotency_record_state_fields;

ALTER TABLE idempotency_record
    ADD CONSTRAINT ck_idempotency_record_state_fields
        CHECK (
            (
                processing_status = 'IN_PROGRESS'
                AND response_snapshot IS NULL
                AND failure_code IS NULL
                AND finished_at IS NULL
            )
            OR (
                processing_status = 'COMPLETED'
                AND financial_transaction_id IS NOT NULL
                AND response_snapshot IS NOT NULL
                AND jsonb_typeof(response_snapshot) = 'object'
                AND failure_code IS NULL
                AND finished_at IS NOT NULL
            )
            OR (
                processing_status = 'FAILED'
                AND response_snapshot IS NULL
                AND failure_code IS NOT NULL
                AND char_length(failure_code) BETWEEN 1 AND 64
                AND failure_code = btrim(failure_code)
                AND finished_at IS NOT NULL
            )
            OR (
                processing_status = 'FAILED'
                AND financial_transaction_id IS NOT NULL
                AND response_snapshot IS NOT NULL
                AND jsonb_typeof(response_snapshot) = 'object'
                AND response_snapshot ?& ARRAY[
                    'snapshotType',
                    'responseBody',
                    'httpStatus',
                    'failureCategory',
                    'responseSchemaVersion',
                    'codecVersion',
                    'finalizedAt'
                ]
                AND response_snapshot - ARRAY[
                    'snapshotType',
                    'responseBody',
                    'httpStatus',
                    'failureCategory',
                    'responseSchemaVersion',
                    'codecVersion',
                    'finalizedAt'
                ] = '{}'::jsonb
                AND response_snapshot -> 'snapshotType'
                    = '"external-risk-failure"'::jsonb
                AND response_snapshot -> 'responseSchemaVersion'
                    = '"transaction-create-error-v1"'::jsonb
                AND response_snapshot -> 'codecVersion'
                    = '"external-risk-failure-snapshot-envelope-v1"'::jsonb
                AND jsonb_typeof(response_snapshot -> 'responseBody') = 'object'
                AND response_snapshot -> 'responseBody' ?& ARRAY[
                    'code',
                    'message',
                    'fieldErrors'
                ]
                AND (response_snapshot -> 'responseBody') - ARRAY[
                    'code',
                    'message',
                    'fieldErrors'
                ] = '{}'::jsonb
                AND response_snapshot -> 'responseBody' -> 'fieldErrors'
                    = '[]'::jsonb
                AND jsonb_typeof(response_snapshot -> 'finalizedAt') = 'string'
                AND failure_code IS NOT NULL
                AND char_length(failure_code) BETWEEN 1 AND 64
                AND failure_code = btrim(failure_code)
                AND response_snapshot -> 'responseBody' ->> 'code'
                    = failure_code
                AND (
                    (
                        response_snapshot ->> 'failureCategory' = 'TIMEOUT'
                        AND response_snapshot -> 'httpStatus' = '503'::jsonb
                        AND failure_code = 'DEPENDENCY_TIMEOUT'
                        AND response_snapshot -> 'responseBody' ->> 'message'
                            = '탐지 서비스를 사용할 수 없습니다.'
                    )
                    OR (
                        response_snapshot ->> 'failureCategory' = 'UNAVAILABLE'
                        AND response_snapshot -> 'httpStatus' = '503'::jsonb
                        AND failure_code = 'DEPENDENCY_UNAVAILABLE'
                        AND response_snapshot -> 'responseBody' ->> 'message'
                            = '탐지 서비스를 사용할 수 없습니다.'
                    )
                    OR (
                        response_snapshot ->> 'failureCategory' IN (
                            'INVALID_REQUEST',
                            'UNSUPPORTED_CAPABILITY',
                            'INVALID_RESPONSE',
                            'TRANSFORMATION_ERROR'
                        )
                        AND response_snapshot -> 'httpStatus' = '500'::jsonb
                        AND failure_code = 'INTERNAL_ERROR'
                        AND response_snapshot -> 'responseBody' ->> 'message'
                            = '요청을 처리하는 중 오류가 발생했습니다.'
                    )
                )
                AND finished_at IS NOT NULL
            )
        );
