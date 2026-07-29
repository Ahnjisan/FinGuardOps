CREATE TABLE behavior_event (
    id BIGINT GENERATED ALWAYS AS IDENTITY,
    event_id UUID NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    external_customer_ref VARCHAR(128) NOT NULL,
    account_ref VARCHAR(128),
    device_ref VARCHAR(128),
    beneficiary_ref VARCHAR(128),
    financial_transaction_id BIGINT,
    request_fingerprint VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_behavior_event
        PRIMARY KEY (id),
    CONSTRAINT uq_behavior_event_event_id
        UNIQUE (event_id),
    CONSTRAINT fk_behavior_event_transaction
        FOREIGN KEY (financial_transaction_id)
        REFERENCES financial_transaction (id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_behavior_event_uuid_v4
        CHECK (
            get_byte(uuid_send(event_id), 6) BETWEEN 64 AND 79
            AND get_byte(uuid_send(event_id), 8) BETWEEN 128 AND 191
        ),
    CONSTRAINT ck_behavior_event_type
        CHECK (
            event_type IN (
                'LOGIN',
                'LOGIN_FAILED',
                'DEVICE_REGISTERED',
                'PASSWORD_CHANGED',
                'OTP_REISSUED',
                'BENEFICIARY_REGISTERED',
                'TRANSFER_LIMIT_CHANGED',
                'TRANSFER_REQUESTED',
                'ATM_WITHDRAWAL_REQUESTED'
            )
        ),
    CONSTRAINT ck_behavior_event_external_customer_ref
        CHECK (
            char_length(external_customer_ref) BETWEEN 1 AND 128
            AND external_customer_ref = btrim(external_customer_ref)
        ),
    CONSTRAINT ck_behavior_event_account_ref
        CHECK (
            account_ref IS NULL
            OR (
                char_length(account_ref) BETWEEN 1 AND 128
                AND account_ref = btrim(account_ref)
            )
        ),
    CONSTRAINT ck_behavior_event_device_ref
        CHECK (
            device_ref IS NULL
            OR (
                char_length(device_ref) BETWEEN 1 AND 128
                AND device_ref = btrim(device_ref)
            )
        ),
    CONSTRAINT ck_behavior_event_beneficiary_ref
        CHECK (
            beneficiary_ref IS NULL
            OR (
                char_length(beneficiary_ref) BETWEEN 1 AND 128
                AND beneficiary_ref = btrim(beneficiary_ref)
            )
        ),
    CONSTRAINT ck_behavior_event_type_fields
        CHECK (
            (
                event_type = 'LOGIN'
                AND device_ref IS NOT NULL
                AND beneficiary_ref IS NULL
            )
            OR (
                event_type = 'LOGIN_FAILED'
                AND beneficiary_ref IS NULL
            )
            OR (
                event_type = 'DEVICE_REGISTERED'
                AND device_ref IS NOT NULL
                AND beneficiary_ref IS NULL
            )
            OR (
                event_type = 'PASSWORD_CHANGED'
                AND beneficiary_ref IS NULL
            )
            OR (
                event_type = 'OTP_REISSUED'
                AND beneficiary_ref IS NULL
            )
            OR (
                event_type = 'BENEFICIARY_REGISTERED'
                AND account_ref IS NOT NULL
                AND beneficiary_ref IS NOT NULL
            )
            OR (
                event_type = 'TRANSFER_LIMIT_CHANGED'
                AND account_ref IS NOT NULL
                AND beneficiary_ref IS NULL
            )
            OR (
                event_type = 'TRANSFER_REQUESTED'
                AND account_ref IS NOT NULL
                AND financial_transaction_id IS NOT NULL
                AND beneficiary_ref IS NULL
            )
            OR (
                event_type = 'ATM_WITHDRAWAL_REQUESTED'
                AND account_ref IS NOT NULL
                AND financial_transaction_id IS NOT NULL
                AND beneficiary_ref IS NULL
            )
        ),
    CONSTRAINT ck_behavior_event_occurred_at
        CHECK (occurred_at <= created_at + INTERVAL '5 minutes'),
    CONSTRAINT ck_behavior_event_fingerprint
        CHECK (request_fingerprint ~ '^[0-9a-f]{64}$')
);

CREATE INDEX ix_behavior_event_transaction
    ON behavior_event (financial_transaction_id)
    WHERE financial_transaction_id IS NOT NULL;
