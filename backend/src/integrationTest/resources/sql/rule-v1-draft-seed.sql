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
    seed.rule_version_id::uuid,
    rule.id,
    1,
    'DRAFT',
    seed.reason_code,
    seed.weight,
    seed.condition_definition::jsonb
FROM (
    VALUES
    (
        'TRANSFER_ABSOLUTE_HIGH_AMOUNT',
        '20000000-0000-4000-8000-000000000001',
        'TRANSFER_ABSOLUTE_HIGH_AMOUNT',
        15,
        '{
            "transactionTypes": [
                "ACCOUNT_TRANSFER",
                "OPEN_BANKING_TRANSFER"
            ],
            "currencyCode": "KRW",
            "amountThreshold": "10000000"
        }'
    ),
    (
        'RECENT_DEVICE_REGISTRATION_HIGH_AMOUNT',
        '20000000-0000-4000-8000-000000000002',
        'RECENT_DEVICE_REGISTRATION_HIGH_AMOUNT',
        20,
        '{
            "prerequisiteRuleCode": "TRANSFER_ABSOLUTE_HIGH_AMOUNT",
            "eventType": "DEVICE_REGISTERED",
            "windowSeconds": 86400,
            "matchPolicy": "SAME_CUSTOMER_AND_DEVICE",
            "selectionPolicy": "LATEST_OCCURRED_AT_THEN_EVENT_ID_ASC"
        }'
    ),
    (
        'RECENT_SECURITY_CHANGE_HIGH_AMOUNT',
        '20000000-0000-4000-8000-000000000003',
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
        }'
    ),
    (
        'RECENT_BENEFICIARY_TRANSFER',
        '20000000-0000-4000-8000-000000000004',
        'RECENT_BENEFICIARY_TRANSFER',
        10,
        '{
            "eventType": "BENEFICIARY_REGISTERED",
            "windowSeconds": 86400,
            "matchPolicy": "SAME_CUSTOMER_SENDER_ACCOUNT_AND_BENEFICIARY",
            "selectionPolicy": "LATEST_OCCURRED_AT_THEN_EVENT_ID_ASC"
        }'
    )
) AS seed(
    rule_code,
    rule_version_id,
    reason_code,
    weight,
    condition_definition
)
JOIN fraud_rule rule ON rule.rule_code = seed.rule_code;
