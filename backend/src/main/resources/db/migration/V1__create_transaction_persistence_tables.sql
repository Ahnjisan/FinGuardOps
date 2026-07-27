CREATE TABLE financial_transaction (
    id BIGINT GENERATED ALWAYS AS IDENTITY,
    transaction_id UUID NOT NULL,
    transaction_type VARCHAR(32) NOT NULL,
    amount NUMERIC(19,4) NOT NULL,
    currency_code VARCHAR(3) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    external_customer_ref VARCHAR(128) NOT NULL,
    sender_account_ref VARCHAR(128) NOT NULL,
    recipient_account_ref VARCHAR(128),
    channel VARCHAR(32) NOT NULL,
    device_ref VARCHAR(128),
    processing_status VARCHAR(32) NOT NULL DEFAULT 'RECEIVED',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_financial_transaction
        PRIMARY KEY (id),
    CONSTRAINT uq_financial_transaction_transaction_id
        UNIQUE (transaction_id),
    CONSTRAINT ck_financial_transaction_uuid_v4
        CHECK (
            get_byte(uuid_send(transaction_id), 6) BETWEEN 64 AND 79
            AND get_byte(uuid_send(transaction_id), 8) BETWEEN 128 AND 191
        ),
    CONSTRAINT ck_financial_transaction_type
        CHECK (
            transaction_type IN (
                'ACCOUNT_TRANSFER',
                'OPEN_BANKING_TRANSFER',
                'ATM_WITHDRAWAL',
                'LOAN_DISBURSED'
            )
        ),
    CONSTRAINT ck_financial_transaction_amount
        CHECK (amount > 0 AND amount = trunc(amount)),
    CONSTRAINT ck_financial_transaction_currency
        CHECK (currency_code = 'KRW'),
    CONSTRAINT ck_financial_transaction_occurred_at
        CHECK (occurred_at <= created_at + INTERVAL '5 minutes'),
    CONSTRAINT ck_financial_transaction_refs
        CHECK (
            char_length(external_customer_ref) BETWEEN 1 AND 128
            AND external_customer_ref = btrim(external_customer_ref)
            AND char_length(sender_account_ref) BETWEEN 1 AND 128
            AND sender_account_ref = btrim(sender_account_ref)
        ),
    CONSTRAINT ck_financial_transaction_type_contract
        CHECK (
            (
                transaction_type = 'ACCOUNT_TRANSFER'
                AND recipient_account_ref IS NOT NULL
                AND char_length(recipient_account_ref) BETWEEN 1 AND 128
                AND recipient_account_ref = btrim(recipient_account_ref)
                AND channel = 'MOBILE_BANKING'
            )
            OR (
                transaction_type = 'OPEN_BANKING_TRANSFER'
                AND recipient_account_ref IS NOT NULL
                AND char_length(recipient_account_ref) BETWEEN 1 AND 128
                AND recipient_account_ref = btrim(recipient_account_ref)
                AND channel = 'OPEN_BANKING'
            )
            OR (
                transaction_type = 'ATM_WITHDRAWAL'
                AND recipient_account_ref IS NULL
                AND channel = 'ATM'
            )
            OR (
                transaction_type = 'LOAN_DISBURSED'
                AND recipient_account_ref IS NULL
                AND channel = 'CORE_BANKING'
            )
        ),
    CONSTRAINT ck_financial_transaction_device_ref
        CHECK (
            device_ref IS NULL
            OR (
                char_length(device_ref) BETWEEN 1 AND 128
                AND device_ref = btrim(device_ref)
            )
        ),
    CONSTRAINT ck_financial_transaction_processing_status
        CHECK (
            processing_status IN (
                'RECEIVED',
                'ANALYZING',
                'ANALYZED',
                'APPROVED',
                'ADDITIONAL_AUTH_REQUIRED',
                'HELD',
                'FAILED'
            )
        ),
    CONSTRAINT ck_financial_transaction_version
        CHECK (version >= 0),
    CONSTRAINT ck_financial_transaction_timestamps
        CHECK (updated_at >= created_at)
);

CREATE TABLE idempotency_record (
    id BIGINT GENERATED ALWAYS AS IDENTITY,
    operation_scope VARCHAR(64) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    processing_status VARCHAR(16) NOT NULL,
    financial_transaction_id BIGINT,
    response_snapshot JSONB,
    failure_code VARCHAR(64),
    expires_at TIMESTAMPTZ NOT NULL
        DEFAULT (CURRENT_TIMESTAMP + INTERVAL '24 hours'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at TIMESTAMPTZ,

    CONSTRAINT pk_idempotency_record
        PRIMARY KEY (id),
    CONSTRAINT uq_idempotency_record_scope_key
        UNIQUE (operation_scope, idempotency_key),
    CONSTRAINT uq_idempotency_record_transaction
        UNIQUE (financial_transaction_id),
    CONSTRAINT fk_idempotency_record_transaction
        FOREIGN KEY (financial_transaction_id)
        REFERENCES financial_transaction (id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_idempotency_record_scope
        CHECK (
            char_length(operation_scope) BETWEEN 1 AND 64
            AND operation_scope = btrim(operation_scope)
        ),
    CONSTRAINT ck_idempotency_record_key_length
        CHECK (char_length(idempotency_key) BETWEEN 8 AND 128),
    CONSTRAINT ck_idempotency_record_key_characters
        CHECK (idempotency_key ~ '^[A-Za-z0-9._:-]+$'),
    CONSTRAINT ck_idempotency_record_fingerprint
        CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_idempotency_record_status
        CHECK (
            processing_status IN ('IN_PROGRESS', 'COMPLETED', 'FAILED')
        ),
    CONSTRAINT ck_idempotency_record_state_fields
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
        ),
    CONSTRAINT ck_idempotency_record_expiration
        CHECK (expires_at = created_at + INTERVAL '24 hours'),
    CONSTRAINT ck_idempotency_record_timestamps
        CHECK (
            updated_at >= created_at
            AND (finished_at IS NULL OR finished_at >= created_at)
        )
);

CREATE INDEX ix_financial_transaction_occurred_at
    ON financial_transaction (occurred_at DESC, id DESC);

CREATE INDEX ix_financial_transaction_type_occurred_at
    ON financial_transaction (transaction_type, occurred_at DESC, id DESC);

CREATE INDEX ix_financial_transaction_status_occurred_at
    ON financial_transaction (processing_status, occurred_at DESC, id DESC);

CREATE INDEX ix_financial_transaction_customer_occurred_at
    ON financial_transaction (
        external_customer_ref,
        occurred_at DESC,
        id DESC
    );

CREATE INDEX ix_financial_transaction_sender_occurred_at
    ON financial_transaction (
        sender_account_ref,
        occurred_at DESC,
        id DESC
    );

CREATE INDEX ix_financial_transaction_recipient_occurred_at
    ON financial_transaction (
        recipient_account_ref,
        occurred_at DESC,
        id DESC
    )
    WHERE recipient_account_ref IS NOT NULL;

CREATE INDEX ix_idempotency_record_expires_at
    ON idempotency_record (expires_at);

CREATE INDEX ix_idempotency_record_status_updated_at
    ON idempotency_record (processing_status, updated_at);
