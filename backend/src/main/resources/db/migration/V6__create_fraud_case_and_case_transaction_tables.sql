CREATE TABLE fraud_case (
    id BIGINT GENERATED ALWAYS AS IDENTITY,
    case_id UUID NOT NULL,
    case_status VARCHAR(48) NOT NULL,
    final_disposition VARCHAR(32),
    assignee_ref VARCHAR(128),
    review_started_at TIMESTAMPTZ,
    closed_at TIMESTAMPTZ,
    concurrency_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    last_changed_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT pk_fraud_case
        PRIMARY KEY (id),
    CONSTRAINT uq_fraud_case_case_id
        UNIQUE (case_id),
    CONSTRAINT ck_fraud_case_uuid_v4
        CHECK (
            get_byte(uuid_send(case_id), 6) BETWEEN 64 AND 79
            AND get_byte(uuid_send(case_id), 8) BETWEEN 128 AND 191
        ),
    CONSTRAINT ck_fraud_case_status
        CHECK (
            case_status IN (
                'OPEN',
                'IN_REVIEW',
                'ADDITIONAL_INFORMATION_REQUIRED',
                'CLOSED'
            )
        ),
    CONSTRAINT ck_fraud_case_final_disposition
        CHECK (
            final_disposition IS NULL
            OR final_disposition IN (
                'NORMAL',
                'FALSE_POSITIVE',
                'CONFIRMED_FRAUD'
            )
        ),
    CONSTRAINT ck_fraud_case_state_fields
        CHECK (
            (
                case_status IN (
                    'OPEN',
                    'IN_REVIEW',
                    'ADDITIONAL_INFORMATION_REQUIRED'
                )
                AND final_disposition IS NULL
                AND closed_at IS NULL
            )
            OR (
                case_status = 'CLOSED'
                AND final_disposition IS NOT NULL
                AND closed_at IS NOT NULL
            )
        ),
    CONSTRAINT ck_fraud_case_in_review_assignee
        CHECK (case_status <> 'IN_REVIEW' OR assignee_ref IS NOT NULL),
    CONSTRAINT ck_fraud_case_assignee_ref
        CHECK (
            assignee_ref IS NULL
            OR (
                char_length(assignee_ref) BETWEEN 1 AND 128
                AND assignee_ref = btrim(assignee_ref)
            )
        ),
    CONSTRAINT ck_fraud_case_concurrency_version
        CHECK (concurrency_version >= 0),
    CONSTRAINT ck_fraud_case_timestamps
        CHECK (
            last_changed_at >= created_at
            AND (
                review_started_at IS NULL
                OR review_started_at >= created_at
            )
            AND (closed_at IS NULL OR closed_at >= created_at)
        )
);

CREATE TABLE case_transaction (
    id BIGINT GENERATED ALWAYS AS IDENTITY,
    fraud_case_id BIGINT NOT NULL,
    financial_transaction_id BIGINT NOT NULL,
    linked_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT pk_case_transaction
        PRIMARY KEY (id),
    CONSTRAINT uq_case_transaction_case_transaction
        UNIQUE (fraud_case_id, financial_transaction_id),
    CONSTRAINT fk_case_transaction_case
        FOREIGN KEY (fraud_case_id)
        REFERENCES fraud_case (id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_case_transaction_transaction
        FOREIGN KEY (financial_transaction_id)
        REFERENCES financial_transaction (id)
        ON DELETE RESTRICT
);

CREATE INDEX ix_fraud_case_status_last_changed
    ON fraud_case (case_status, last_changed_at, id);

CREATE INDEX ix_case_transaction_transaction_case
    ON case_transaction (financial_transaction_id, fraud_case_id);
