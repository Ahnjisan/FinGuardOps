CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE fraud_rule (
    id BIGINT GENERATED ALWAYS AS IDENTITY,
    fraud_rule_id UUID NOT NULL,
    rule_code VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    description VARCHAR(512) NOT NULL,
    lifecycle_status VARCHAR(16) NOT NULL,
    concurrency_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_fraud_rule
        PRIMARY KEY (id),
    CONSTRAINT uq_fraud_rule_business_id
        UNIQUE (fraud_rule_id),
    CONSTRAINT uq_fraud_rule_rule_code
        UNIQUE (rule_code),
    CONSTRAINT ck_fraud_rule_uuid_v4
        CHECK (
            get_byte(uuid_send(fraud_rule_id), 6) BETWEEN 64 AND 79
            AND get_byte(uuid_send(fraud_rule_id), 8) BETWEEN 128 AND 191
        ),
    CONSTRAINT ck_fraud_rule_rule_code
        CHECK (rule_code ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    CONSTRAINT ck_fraud_rule_name
        CHECK (
            char_length(name) BETWEEN 1 AND 128
            AND name = btrim(name)
        ),
    CONSTRAINT ck_fraud_rule_description
        CHECK (
            char_length(description) BETWEEN 1 AND 512
            AND description = btrim(description)
        ),
    CONSTRAINT ck_fraud_rule_lifecycle_status
        CHECK (lifecycle_status IN ('ACTIVE', 'RETIRED')),
    CONSTRAINT ck_fraud_rule_concurrency_version
        CHECK (concurrency_version >= 0),
    CONSTRAINT ck_fraud_rule_timestamps
        CHECK (updated_at >= created_at)
);

CREATE TABLE rule_version (
    id BIGINT GENERATED ALWAYS AS IDENTITY,
    rule_version_id UUID NOT NULL,
    fraud_rule_id BIGINT NOT NULL,
    version_number INTEGER NOT NULL,
    status VARCHAR(16) NOT NULL,
    reason_code VARCHAR(64) NOT NULL,
    weight INTEGER NOT NULL,
    condition_definition JSONB NOT NULL,
    effective_from TIMESTAMPTZ,
    effective_to TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMPTZ,
    concurrency_version BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT pk_rule_version
        PRIMARY KEY (id),
    CONSTRAINT uq_rule_version_business_id
        UNIQUE (rule_version_id),
    CONSTRAINT uq_rule_version_rule_number
        UNIQUE (fraud_rule_id, version_number),
    CONSTRAINT fk_rule_version_fraud_rule
        FOREIGN KEY (fraud_rule_id)
        REFERENCES fraud_rule (id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_rule_version_uuid_v4
        CHECK (
            get_byte(uuid_send(rule_version_id), 6) BETWEEN 64 AND 79
            AND get_byte(uuid_send(rule_version_id), 8) BETWEEN 128 AND 191
        ),
    CONSTRAINT ck_rule_version_number
        CHECK (version_number >= 1),
    CONSTRAINT ck_rule_version_status
        CHECK (status IN ('DRAFT', 'PUBLISHED', 'WITHDRAWN')),
    CONSTRAINT ck_rule_version_reason_code
        CHECK (reason_code ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    CONSTRAINT ck_rule_version_weight
        CHECK (weight BETWEEN 1 AND 100),
    CONSTRAINT ck_rule_version_condition_definition
        CHECK (
            jsonb_typeof(condition_definition) = 'object'
            AND condition_definition <> '{}'::jsonb
        ),
    CONSTRAINT ck_rule_version_effective_period
        CHECK (
            effective_to IS NULL
            OR (
                effective_from IS NOT NULL
                AND effective_to > effective_from
            )
        ),
    CONSTRAINT ck_rule_version_status_fields
        CHECK (
            (
                status = 'DRAFT'
                AND published_at IS NULL
            )
            OR (
                status = 'PUBLISHED'
                AND effective_from IS NOT NULL
                AND published_at IS NOT NULL
            )
            OR (
                status = 'WITHDRAWN'
                AND published_at IS NULL
            )
        ),
    CONSTRAINT ck_rule_version_concurrency_version
        CHECK (concurrency_version >= 0)
);

ALTER TABLE rule_version
    ADD CONSTRAINT ex_rule_version_published_effective_period
    EXCLUDE USING gist (
        fraud_rule_id WITH =,
        tstzrange(effective_from, effective_to, '[)') WITH &&
    )
    WHERE (status = 'PUBLISHED');

CREATE INDEX ix_rule_version_rule_status_effective
    ON rule_version (
        fraud_rule_id,
        status,
        effective_from DESC,
        effective_to,
        version_number DESC
    );

CREATE FUNCTION guard_fraud_rule_history()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION
            'fraud rules cannot be physically deleted'
            USING ERRCODE = '55000';
    END IF;

    IF NEW.id IS DISTINCT FROM OLD.id
        OR NEW.fraud_rule_id IS DISTINCT FROM OLD.fraud_rule_id
        OR NEW.rule_code IS DISTINCT FROM OLD.rule_code
        OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION
            'immutable fraud rule fields cannot be changed'
            USING ERRCODE = '55000';
    END IF;

    IF NOT (
        NEW.lifecycle_status = OLD.lifecycle_status
        OR (
            OLD.lifecycle_status = 'ACTIVE'
            AND NEW.lifecycle_status = 'RETIRED'
        )
    ) THEN
        RAISE EXCEPTION
            'invalid fraud rule lifecycle transition'
            USING ERRCODE = '55000';
    END IF;

    RETURN NEW;
END
$$;

CREATE TRIGGER tg_fraud_rule_history_guard
BEFORE UPDATE OR DELETE ON fraud_rule
FOR EACH ROW
EXECUTE FUNCTION guard_fraud_rule_history();

CREATE FUNCTION guard_rule_version_history()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION
            'rule versions cannot be physically deleted'
            USING ERRCODE = '55000';
    END IF;

    IF NEW.id IS DISTINCT FROM OLD.id
        OR NEW.rule_version_id IS DISTINCT FROM OLD.rule_version_id
        OR NEW.fraud_rule_id IS DISTINCT FROM OLD.fraud_rule_id
        OR NEW.version_number IS DISTINCT FROM OLD.version_number
        OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION
            'immutable rule version identity fields cannot be changed'
            USING ERRCODE = '55000';
    END IF;

    IF OLD.status = 'WITHDRAWN' THEN
        RAISE EXCEPTION
            'withdrawn rule versions are immutable'
            USING ERRCODE = '55000';
    END IF;

    IF OLD.status = 'DRAFT'
        AND NEW.status NOT IN ('DRAFT', 'PUBLISHED', 'WITHDRAWN') THEN
        RAISE EXCEPTION
            'invalid draft rule version transition'
            USING ERRCODE = '55000';
    END IF;

    IF OLD.status = 'PUBLISHED' THEN
        IF NEW.status <> 'PUBLISHED'
            OR NEW.reason_code IS DISTINCT FROM OLD.reason_code
            OR NEW.weight IS DISTINCT FROM OLD.weight
            OR NEW.condition_definition
                IS DISTINCT FROM OLD.condition_definition
            OR NEW.effective_from IS DISTINCT FROM OLD.effective_from
            OR NEW.published_at IS DISTINCT FROM OLD.published_at THEN
            RAISE EXCEPTION
                'published rule version definition is immutable'
                USING ERRCODE = '55000';
        END IF;

        IF OLD.effective_to IS NOT NULL
            AND NEW.effective_to IS DISTINCT FROM OLD.effective_to THEN
            RAISE EXCEPTION
                'published effectiveTo can only be set once'
                USING ERRCODE = '55000';
        END IF;
    END IF;

    RETURN NEW;
END
$$;

CREATE TRIGGER tg_rule_version_history_guard
BEFORE UPDATE OR DELETE ON rule_version
FOR EACH ROW
EXECUTE FUNCTION guard_rule_version_history();

INSERT INTO fraud_rule (
    fraud_rule_id,
    rule_code,
    name,
    description,
    lifecycle_status
) VALUES
(
    '10000000-0000-4000-8000-000000000001',
    'TRANSFER_ABSOLUTE_HIGH_AMOUNT',
    '절대 고액 이체',
    '현재 KRW 이체 금액이 검증용 절대 임계값 이상인지 평가한다.',
    'ACTIVE'
),
(
    '10000000-0000-4000-8000-000000000002',
    'RECENT_DEVICE_REGISTRATION_HIGH_AMOUNT',
    '최근 기기 등록 고액 이체',
    '최근 기기 등록 이벤트의 존재를 신규 기기 위험 프록시로 사용한다.',
    'ACTIVE'
),
(
    '10000000-0000-4000-8000-000000000003',
    'RECENT_SECURITY_CHANGE_HIGH_AMOUNT',
    '최근 보안정보 변경 고액 이체',
    '비밀번호 변경 후 이체 한도 변경 이벤트 시퀀스를 위험 프록시로 사용한다.',
    'ACTIVE'
),
(
    '10000000-0000-4000-8000-000000000004',
    'RECENT_BENEFICIARY_TRANSFER',
    '최근 등록 수취인 이체',
    '최근 수취인 등록 이벤트의 존재를 신규 수취인 위험 프록시로 사용한다.',
    'ACTIVE'
);

INSERT INTO rule_version (
    rule_version_id,
    fraud_rule_id,
    version_number,
    status,
    reason_code,
    weight,
    condition_definition
)
SELECT
    '20000000-0000-4000-8000-000000000001',
    rule.id,
    1,
    'DRAFT',
    'TRANSFER_ABSOLUTE_HIGH_AMOUNT',
    15,
    '{
        "transactionTypes": [
            "ACCOUNT_TRANSFER",
            "OPEN_BANKING_TRANSFER"
        ],
        "currencyCode": "KRW",
        "amountThreshold": "10000000"
    }'::jsonb
FROM fraud_rule rule
WHERE rule.rule_code = 'TRANSFER_ABSOLUTE_HIGH_AMOUNT';

INSERT INTO rule_version (
    rule_version_id,
    fraud_rule_id,
    version_number,
    status,
    reason_code,
    weight,
    condition_definition
)
SELECT
    '20000000-0000-4000-8000-000000000002',
    rule.id,
    1,
    'DRAFT',
    'RECENT_DEVICE_REGISTRATION_HIGH_AMOUNT',
    20,
    '{
        "prerequisiteRuleCode": "TRANSFER_ABSOLUTE_HIGH_AMOUNT",
        "eventType": "DEVICE_REGISTERED",
        "windowSeconds": 86400,
        "matchPolicy": "SAME_CUSTOMER_AND_DEVICE",
        "selectionPolicy": "LATEST_OCCURRED_AT_THEN_EVENT_ID_ASC"
    }'::jsonb
FROM fraud_rule rule
WHERE rule.rule_code = 'RECENT_DEVICE_REGISTRATION_HIGH_AMOUNT';

INSERT INTO rule_version (
    rule_version_id,
    fraud_rule_id,
    version_number,
    status,
    reason_code,
    weight,
    condition_definition
)
SELECT
    '20000000-0000-4000-8000-000000000003',
    rule.id,
    1,
    'DRAFT',
    'RECENT_SECURITY_CHANGE_HIGH_AMOUNT',
    40,
    '{
        "prerequisiteRuleCode": "TRANSFER_ABSOLUTE_HIGH_AMOUNT",
        "passwordEventType": "PASSWORD_CHANGED",
        "transferLimitEventType": "TRANSFER_LIMIT_CHANGED",
        "windowSeconds": 86400,
        "matchPolicy": "SAME_CUSTOMER_AND_SENDER_ACCOUNT",
        "sequencePolicy": "PASSWORD_CHANGED_AT_OR_BEFORE_TRANSFER_LIMIT_CHANGED",
        "selectionPolicy": "LATEST_TRANSFER_LIMIT_THEN_EVENT_ID_ASC_LATEST_PASSWORD_THEN_EVENT_ID_ASC"
    }'::jsonb
FROM fraud_rule rule
WHERE rule.rule_code = 'RECENT_SECURITY_CHANGE_HIGH_AMOUNT';

INSERT INTO rule_version (
    rule_version_id,
    fraud_rule_id,
    version_number,
    status,
    reason_code,
    weight,
    condition_definition
)
SELECT
    '20000000-0000-4000-8000-000000000004',
    rule.id,
    1,
    'DRAFT',
    'RECENT_BENEFICIARY_TRANSFER',
    10,
    '{
        "eventType": "BENEFICIARY_REGISTERED",
        "windowSeconds": 86400,
        "matchPolicy": "SAME_CUSTOMER_SENDER_ACCOUNT_AND_BENEFICIARY",
        "selectionPolicy": "LATEST_OCCURRED_AT_THEN_EVENT_ID_ASC"
    }'::jsonb
FROM fraud_rule rule
WHERE rule.rule_code = 'RECENT_BENEFICIARY_TRANSFER';

ALTER TABLE detection_evidence
    ADD COLUMN rule_version_id BIGINT;

ALTER TABLE detection_evidence
    ADD CONSTRAINT fk_detection_evidence_rule_version
        FOREIGN KEY (rule_version_id)
        REFERENCES rule_version (id)
        ON DELETE RESTRICT,
    ADD CONSTRAINT ck_detection_evidence_rule_version_type
        CHECK (
            evidence_type = 'RULE'
            OR rule_version_id IS NULL
        );

CREATE INDEX ix_detection_evidence_rule_version_id
    ON detection_evidence (rule_version_id)
    WHERE rule_version_id IS NOT NULL;

CREATE OR REPLACE FUNCTION guard_detection_evidence_history()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    parent_status VARCHAR(16);
    referenced_rule_code VARCHAR(64);
    referenced_version_number INTEGER;
    referenced_reason_code VARCHAR(64);
    referenced_weight INTEGER;
    referenced_version_status VARCHAR(16);
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

    IF NEW.rule_version_id IS NULL THEN
        RETURN NEW;
    END IF;

    SELECT
        rule.rule_code,
        version.version_number,
        version.reason_code,
        version.weight,
        version.status
    INTO
        referenced_rule_code,
        referenced_version_number,
        referenced_reason_code,
        referenced_weight,
        referenced_version_status
    FROM rule_version version
    JOIN fraud_rule rule ON rule.id = version.fraud_rule_id
    WHERE version.id = NEW.rule_version_id;

    IF referenced_version_status IS DISTINCT FROM 'PUBLISHED'
        OR NEW.evidence_type <> 'RULE'
        OR NEW.rule_code IS DISTINCT FROM referenced_rule_code
        OR NEW.rule_version
            IS DISTINCT FROM referenced_version_number::text
        OR NEW.reason_code IS DISTINCT FROM referenced_reason_code
        OR NEW.score_contribution IS DISTINCT FROM referenced_weight THEN
        RAISE EXCEPTION
            'ck_detection_evidence_rule_version_snapshot: evidence snapshot does not match published rule version'
            USING ERRCODE = '23514',
                CONSTRAINT =
                    'ck_detection_evidence_rule_version_snapshot';
    END IF;

    RETURN NEW;
END
$$;
