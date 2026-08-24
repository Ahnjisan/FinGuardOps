import json
from copy import deepcopy
from dataclasses import FrozenInstanceError

import pytest
from pydantic import BaseModel, ValidationError

from finguardops_ai.rules.v1 import (
    BehaviorEventType,
    FraudRuleLifecycleStatus,
    RuleVersionStatus,
    TransactionType,
)
from finguardops_ai.schemas.rule_analysis import (
    ExternalRiskLookupStatus,
    ExternalRiskMatchRequest,
    ExternalRiskPolicyResult,
    ExternalRiskSnapshotRequest,
    R001EvidenceResponse,
    R001ObservationResponse,
    R002EvidenceResponse,
    R002ObservationResponse,
    R003EvidenceResponse,
    R003ObservationResponse,
    R004EvidenceResponse,
    R004ObservationResponse,
    RuleAnalysisRequest,
    RuleAnalysisRequestV2,
    RuleAnalysisResponse,
    RuleAnalysisResultResponse,
    RuleBehaviorEventSnapshotRequest,
    RuleContributionResponse,
    RuleScoreGroupSummaryResponse,
    RuleScoringResultResponse,
    RuleTransactionSnapshotRequest,
    RuleVersionSnapshotRequest,
)


def _valid_request() -> dict[str, object]:
    return {
        "evaluationCutoffAt": "2026-07-23T12:00:00Z",
        "transaction": {
            "transactionId": "10000000-0000-4000-8000-000000000001",
            "transactionType": "ACCOUNT_TRANSFER",
            "amount": "12000000",
            "currencyCode": "KRW",
            "occurredAt": "2026-07-23T12:00:00Z",
            "externalCustomerRef": "customer-a",
            "senderAccountRef": "sender-a",
            "recipientAccountRef": "recipient-a",
            "deviceRef": "device-a",
        },
        "behaviorEvents": [
            {
                "eventId": "30000000-0000-4000-8000-000000000001",
                "eventType": "DEVICE_REGISTERED",
                "occurredAt": "2026-07-23T11:55:00Z",
                "externalCustomerRef": "customer-a",
                "accountRef": None,
                "deviceRef": "device-a",
                "beneficiaryRef": None,
            },
            {
                "eventId": "30000000-0000-4000-8000-000000000002",
                "eventType": "PASSWORD_CHANGED",
                "occurredAt": "2026-07-23T11:56:00.123456Z",
                "externalCustomerRef": "customer-a",
                "accountRef": None,
                "deviceRef": None,
                "beneficiaryRef": None,
            },
            {
                "eventId": "30000000-0000-4000-8000-000000000003",
                "eventType": "TRANSFER_LIMIT_CHANGED",
                "occurredAt": "2026-07-23T11:57:00Z",
                "externalCustomerRef": "customer-a",
                "accountRef": "sender-a",
                "deviceRef": None,
                "beneficiaryRef": None,
            },
            {
                "eventId": "30000000-0000-4000-8000-000000000004",
                "eventType": "BENEFICIARY_REGISTERED",
                "occurredAt": "2026-07-23T11:59:00Z",
                "externalCustomerRef": "customer-a",
                "accountRef": "sender-a",
                "deviceRef": None,
                "beneficiaryRef": "recipient-a",
            },
        ],
        "ruleVersions": [
            _rule_version(
                1,
                "TRANSFER_ABSOLUTE_HIGH_AMOUNT",
                15,
                {
                    "transactionTypes": ["ACCOUNT_TRANSFER", "OPEN_BANKING_TRANSFER"],
                    "currencyCode": "KRW",
                    "amountThreshold": "10000000",
                },
            ),
            _rule_version(
                2,
                "RECENT_DEVICE_REGISTRATION_HIGH_AMOUNT",
                20,
                {
                    "prerequisiteRuleCode": "TRANSFER_ABSOLUTE_HIGH_AMOUNT",
                    "eventType": "DEVICE_REGISTERED",
                    "windowSeconds": 86400,
                    "matchPolicy": "SAME_CUSTOMER_AND_DEVICE",
                    "selectionPolicy": "LATEST_OCCURRED_AT_THEN_EVENT_ID_ASC",
                },
            ),
            _rule_version(
                3,
                "RECENT_SECURITY_CHANGE_HIGH_AMOUNT",
                40,
                {
                    "prerequisiteRuleCode": "TRANSFER_ABSOLUTE_HIGH_AMOUNT",
                    "passwordEventType": "PASSWORD_CHANGED",
                    "transferLimitEventType": "TRANSFER_LIMIT_CHANGED",
                    "windowSeconds": 86400,
                    "matchPolicy": "SAME_CUSTOMER_AND_SENDER_ACCOUNT",
                    "sequencePolicy": "PASSWORD_CHANGED_AT_OR_BEFORE_TRANSFER_LIMIT_CHANGED",
                    "selectionPolicy": (
                        "LATEST_TRANSFER_LIMIT_THEN_EVENT_ID_ASC_LATEST_PASSWORD_THEN_EVENT_ID_ASC"
                    ),
                },
            ),
            _rule_version(
                4,
                "RECENT_BENEFICIARY_TRANSFER",
                10,
                {
                    "eventType": "BENEFICIARY_REGISTERED",
                    "windowSeconds": 86400,
                    "matchPolicy": "SAME_CUSTOMER_SENDER_ACCOUNT_AND_BENEFICIARY",
                    "selectionPolicy": "LATEST_OCCURRED_AT_THEN_EVENT_ID_ASC",
                },
            ),
        ],
    }


def _valid_external_risk(*, matched: bool = True) -> dict[str, object]:
    return {
        "providerCode": "EXTERNAL_RISK_MOCK_V1",
        "lookupStatus": "SUCCEEDED",
        "policyResult": "MATCHED" if matched else "UNMATCHED",
        "providerAsOf": "2026-07-23T11:59:59.123456Z",
        "lookedUpAt": "2026-07-23T12:00:00.654321Z",
        "matches": [
            {
                "subjectType": "SENDER_ACCOUNT",
                "externalRiskType": "SUSPICIOUS_ACCOUNT",
                "reasonCode": "SUSPICIOUS_SENDER_ACCOUNT",
            }
        ]
        if matched
        else [],
    }


def _valid_v2_request(*, matched: bool = True) -> dict[str, object]:
    payload = _valid_request()
    payload["externalRisk"] = _valid_external_risk(matched=matched)
    return payload


def _rule_version(
    number: int,
    rule_code: str,
    weight: int,
    condition_definition: dict[str, object],
) -> dict[str, object]:
    return {
        "fraudRuleId": f"10000000-0000-4000-8000-00000000001{number}",
        "ruleCode": rule_code,
        "lifecycleStatus": "ACTIVE",
        "ruleVersionId": f"20000000-0000-4000-8000-00000000000{number}",
        "versionNumber": number,
        "status": "PUBLISHED",
        "reasonCode": rule_code,
        "weight": weight,
        "conditionDefinition": condition_definition,
        "effectiveFrom": "2026-07-22T12:00:00Z",
        "effectiveTo": None,
    }


def _replace(payload: dict[str, object], path: tuple[object, ...], value: object) -> None:
    target: object = payload
    for part in path[:-1]:
        target = target[part]  # type: ignore[index]
    target[path[-1]] = value  # type: ignore[index]


def _valid_response() -> dict[str, object]:
    common = {
        "scoringPolicyVersion": "scoring-policy-v1",
        "riskScore": 75,
        "riskLevel": "HIGH",
        "ruleContributions": [
            {
                "ruleId": "R001",
                "executionOrder": 1,
                "matched": True,
                "originalContribution": 15,
            }
        ],
        "groupSummaries": [
            {
                "groupId": "amount",
                "rawScore": 15,
                "cap": 15,
                "appliedScore": 15,
                "reduction": 0,
            }
        ],
    }
    evidence = [
        {
            "ruleId": "R001",
            "ruleVersionId": "20000000-0000-4000-8000-000000000001",
            "ruleCode": "TRANSFER_ABSOLUTE_HIGH_AMOUNT",
            "ruleVersion": "1",
            "reasonCode": "TRANSFER_ABSOLUTE_HIGH_AMOUNT",
            "executionOrder": 1,
            "scoreContribution": 15,
            "observationSummary": {
                "observedAmount": "12000000",
                "amountThreshold": "10000000",
            },
            "evidenceOccurredAt": "2026-07-23T12:00:00Z",
        },
        {
            "ruleId": "R002",
            "ruleVersionId": "20000000-0000-4000-8000-000000000002",
            "ruleCode": "RECENT_DEVICE_REGISTRATION_HIGH_AMOUNT",
            "ruleVersion": "2",
            "reasonCode": "RECENT_DEVICE_REGISTRATION_HIGH_AMOUNT",
            "executionOrder": 2,
            "scoreContribution": 20,
            "observationSummary": {
                "observedAmount": "12000000",
                "amountThreshold": "10000000",
                "eventId": "30000000-0000-4000-8000-000000000001",
                "deviceRegisteredAt": "2026-07-23T11:55:00Z",
                "elapsedSeconds": 300,
                "windowSeconds": 86400,
            },
            "evidenceOccurredAt": "2026-07-23T11:55:00Z",
        },
        {
            "ruleId": "R003",
            "ruleVersionId": "20000000-0000-4000-8000-000000000003",
            "ruleCode": "RECENT_SECURITY_CHANGE_HIGH_AMOUNT",
            "ruleVersion": "3",
            "reasonCode": "RECENT_SECURITY_CHANGE_HIGH_AMOUNT",
            "executionOrder": 3,
            "scoreContribution": 40,
            "observationSummary": {
                "observedAmount": "12000000",
                "amountThreshold": "10000000",
                "passwordChangedEventId": "30000000-0000-4000-8000-000000000002",
                "passwordChangedAt": "2026-07-23T11:56:00.123456Z",
                "transferLimitChangedEventId": "30000000-0000-4000-8000-000000000003",
                "transferLimitChangedAt": "2026-07-23T11:57:00Z",
                "elapsedSeconds": 180,
                "windowSeconds": 86400,
            },
            "evidenceOccurredAt": "2026-07-23T11:57:00Z",
        },
        {
            "ruleId": "R004",
            "ruleVersionId": "20000000-0000-4000-8000-000000000004",
            "ruleCode": "RECENT_BENEFICIARY_TRANSFER",
            "ruleVersion": "4",
            "reasonCode": "RECENT_BENEFICIARY_TRANSFER",
            "executionOrder": 4,
            "scoreContribution": 10,
            "observationSummary": {
                "observedAmount": "12000000",
                "eventId": "30000000-0000-4000-8000-000000000004",
                "beneficiaryRegisteredAt": "2026-07-23T11:59:00Z",
                "elapsedSeconds": 60,
                "windowSeconds": 86400,
            },
            "evidenceOccurredAt": "2026-07-23T11:59:00Z",
        },
    ]
    return {
        "transactionId": "10000000-0000-4000-8000-000000000001",
        "traceId": "trace_demo_rule_0001",
        "analysis": {
            "evaluationCutoffAt": "2026-07-23T12:00:00Z",
            "ruleSetVersion": "a" * 64,
            "scoringResult": common,
            "evidence": evidence,
        },
    }


def _nested_dto_cases():
    request = _valid_request()
    response = _valid_response()
    scoring = response["analysis"]["scoringResult"]  # type: ignore[index]
    evidence = response["analysis"]["evidence"]  # type: ignore[index]
    return [
        pytest.param(
            RuleTransactionSnapshotRequest,
            request["transaction"],
            "transactionId",
            "transaction_id",
            "amount",
            12000000,
            id="request-transaction",
        ),
        pytest.param(
            RuleBehaviorEventSnapshotRequest,
            request["behaviorEvents"][0],  # type: ignore[index]
            "eventId",
            "event_id",
            "occurredAt",
            1,
            id="request-behavior-event",
        ),
        pytest.param(
            RuleVersionSnapshotRequest,
            request["ruleVersions"][0],  # type: ignore[index]
            "ruleVersionId",
            "rule_version_id",
            "weight",
            "15",
            id="request-rule-version",
        ),
        pytest.param(
            RuleAnalysisRequest,
            request,
            "evaluationCutoffAt",
            "evaluation_cutoff_at",
            "evaluationCutoffAt",
            1,
            id="request-envelope",
        ),
        pytest.param(
            RuleContributionResponse,
            scoring["ruleContributions"][0],  # type: ignore[index]
            "ruleId",
            "rule_id",
            "matched",
            1,
            id="response-contribution",
        ),
        pytest.param(
            RuleScoreGroupSummaryResponse,
            scoring["groupSummaries"][0],  # type: ignore[index]
            "groupId",
            "group_id",
            "rawScore",
            "15",
            id="response-group-summary",
        ),
        pytest.param(
            RuleScoringResultResponse,
            scoring,
            "scoringPolicyVersion",
            "scoring_policy_version",
            "riskScore",
            "75",
            id="response-scoring",
        ),
        pytest.param(
            R001ObservationResponse,
            evidence[0]["observationSummary"],
            "observedAmount",
            "observed_amount",
            "observedAmount",
            12000000,
            id="response-r001-observation",
        ),
        pytest.param(
            R002ObservationResponse,
            evidence[1]["observationSummary"],
            "deviceRegisteredAt",
            "device_registered_at",
            "elapsedSeconds",
            "300",
            id="response-r002-observation",
        ),
        pytest.param(
            R003ObservationResponse,
            evidence[2]["observationSummary"],
            "passwordChangedEventId",
            "password_changed_event_id",
            "windowSeconds",
            "86400",
            id="response-r003-observation",
        ),
        pytest.param(
            R004ObservationResponse,
            evidence[3]["observationSummary"],
            "beneficiaryRegisteredAt",
            "beneficiary_registered_at",
            "elapsedSeconds",
            "60",
            id="response-r004-observation",
        ),
        pytest.param(
            R001EvidenceResponse,
            evidence[0],
            "ruleVersionId",
            "rule_version_id",
            "executionOrder",
            "1",
            id="response-r001-evidence",
        ),
        pytest.param(
            R002EvidenceResponse,
            evidence[1],
            "ruleVersionId",
            "rule_version_id",
            "scoreContribution",
            "20",
            id="response-r002-evidence",
        ),
        pytest.param(
            R003EvidenceResponse,
            evidence[2],
            "ruleVersionId",
            "rule_version_id",
            "executionOrder",
            "3",
            id="response-r003-evidence",
        ),
        pytest.param(
            R004EvidenceResponse,
            evidence[3],
            "ruleVersionId",
            "rule_version_id",
            "scoreContribution",
            "10",
            id="response-r004-evidence",
        ),
        pytest.param(
            RuleAnalysisResultResponse,
            response["analysis"],
            "evaluationCutoffAt",
            "evaluation_cutoff_at",
            "ruleSetVersion",
            1,
            id="response-analysis",
        ),
        pytest.param(
            RuleAnalysisResponse,
            response,
            "transactionId",
            "transaction_id",
            "traceId",
            1,
            id="response-envelope",
        ),
    ]


def test_official_request_deserializes_with_exact_nested_types() -> None:
    request = RuleAnalysisRequest.model_validate(_valid_request())

    assert request.transaction.transaction_type is TransactionType.ACCOUNT_TRANSFER
    assert request.behavior_events[0].event_type is BehaviorEventType.DEVICE_REGISTERED
    assert request.rule_versions[0].lifecycle_status is FraudRuleLifecycleStatus.ACTIVE
    assert request.rule_versions[0].status is RuleVersionStatus.PUBLISHED
    assert request.transaction.amount.as_tuple().exponent == 0
    assert request.behavior_events[1].occurred_at.microsecond == 123456
    assert isinstance(request.behavior_events, tuple)
    assert isinstance(request.rule_versions, tuple)


@pytest.mark.parametrize(
    ("path", "snake_name"),
    [
        (("evaluationCutoffAt",), "evaluation_cutoff_at"),
        (("transaction", "transactionId"), "transaction_id"),
        (("behaviorEvents", 0, "eventId"), "event_id"),
        (("ruleVersions", 0, "ruleVersionId"), "rule_version_id"),
    ],
)
def test_request_accepts_json_aliases_only(path: tuple[object, ...], snake_name: str) -> None:
    payload = _valid_request()
    target: object = payload
    for part in path[:-1]:
        target = target[part]  # type: ignore[index]
    alias = path[-1]
    target[snake_name] = target.pop(alias)  # type: ignore[attr-defined]

    with pytest.raises(ValidationError):
        RuleAnalysisRequest.model_validate(payload)


@pytest.mark.parametrize(
    "path",
    [
        (),
        ("transaction",),
        ("behaviorEvents", 0),
        ("ruleVersions", 0),
    ],
)
def test_all_structured_request_dtos_reject_extra_fields(path: tuple[object, ...]) -> None:
    payload = _valid_request()
    target: object = payload
    for part in path:
        target = target[part]  # type: ignore[index]
    target["unexpected"] = "value"  # type: ignore[index]

    with pytest.raises(ValidationError) as exc_info:
        RuleAnalysisRequest.model_validate(payload)

    assert any(error["type"] == "extra_forbidden" for error in exc_info.value.errors())


@pytest.mark.parametrize(
    ("path", "value"),
    [
        (("transaction", "amount"), 12000000),
        (("transaction", "currencyCode"), 410),
        (("ruleVersions", 0, "versionNumber"), "1"),
        (("ruleVersions", 0, "weight"), True),
        (("behaviorEvents", 0, "eventType"), 1),
        (("behaviorEvents",), "not-an-array"),
    ],
)
def test_request_rejects_scalar_coercion(path: tuple[object, ...], value: object) -> None:
    payload = _valid_request()
    _replace(payload, path, value)

    with pytest.raises(ValidationError):
        RuleAnalysisRequest.model_validate(payload)


@pytest.mark.parametrize(
    "value",
    [
        "10000000-0000-1000-8000-000000000001",
        "10000000-0000-4000-7000-000000000001",
        "10000000-0000-4000-8000-00000000000A",
        "10000000000040008000000000000001",
    ],
)
def test_request_rejects_noncanonical_uuid_v4(value: str) -> None:
    payload = _valid_request()
    _replace(payload, ("transaction", "transactionId"), value)

    with pytest.raises(ValidationError):
        RuleAnalysisRequest.model_validate(payload)


@pytest.mark.parametrize(
    "value",
    [
        "2026-07-23T12:00:00+00:00",
        "2026-07-23T12:00:00",
        "2026-07-23T12:00:00.1234567Z",
        "2026-07-23t12:00:00z",
    ],
)
def test_request_rejects_noncanonical_utc_z_timestamp(value: str) -> None:
    payload = _valid_request()
    _replace(payload, ("evaluationCutoffAt",), value)

    with pytest.raises(ValidationError):
        RuleAnalysisRequest.model_validate(payload)


@pytest.mark.parametrize(
    "value",
    ["0", "012000000", "12000000.0", "1e7", "+12000000", " 12000000"],
)
def test_request_rejects_noncanonical_decimal_string(value: str) -> None:
    payload = _valid_request()
    _replace(payload, ("transaction", "amount"), value)

    with pytest.raises(ValidationError):
        RuleAnalysisRequest.model_validate(payload)


@pytest.mark.parametrize(
    ("path", "value"),
    [
        (("transaction", "transactionType"), "account_transfer"),
        (("behaviorEvents", 0, "eventType"), "device_registered"),
        (("ruleVersions", 0, "lifecycleStatus"), "active"),
        (("ruleVersions", 0, "status"), "published"),
    ],
)
def test_request_rejects_nonexact_enum_wire_values(
    path: tuple[object, ...],
    value: str,
) -> None:
    payload = _valid_request()
    _replace(payload, path, value)

    with pytest.raises(ValidationError):
        RuleAnalysisRequest.model_validate(payload)


def test_nested_required_field_and_reference_format_are_wire_validation_errors() -> None:
    missing = _valid_request()
    missing["behaviorEvents"][0].pop("beneficiaryRef")  # type: ignore[index,union-attr]
    padded = _valid_request()
    _replace(padded, ("transaction", "externalCustomerRef"), " customer-a")

    with pytest.raises(ValidationError):
        RuleAnalysisRequest.model_validate(missing)
    with pytest.raises(ValidationError):
        RuleAnalysisRequest.model_validate(padded)


def test_request_models_are_frozen() -> None:
    request = RuleAnalysisRequest.model_validate(_valid_request())

    with pytest.raises((ValidationError, FrozenInstanceError)):
        request.evaluation_cutoff_at = request.transaction.occurred_at


def test_condition_definition_is_deeply_immutable_and_isolated_from_source() -> None:
    payload = _valid_request()
    condition = payload["ruleVersions"][0]["conditionDefinition"]  # type: ignore[index]
    condition["nestedObject"] = {  # type: ignore[index]
        "label": "original",
        "nestedArray": [{"value": "original"}],
    }
    request = RuleAnalysisRequest.model_validate(payload)
    frozen = request.rule_versions[0].condition_definition

    condition["amountThreshold"] = "9999999"  # type: ignore[index]
    condition["nestedObject"]["label"] = "changed"  # type: ignore[index]
    condition["nestedObject"]["nestedArray"].append({"value": "added"})  # type: ignore[index]

    assert frozen["amountThreshold"] == "10000000"
    assert frozen["nestedObject"]["label"] == "original"  # type: ignore[index]
    assert frozen["nestedObject"]["nestedArray"] == (  # type: ignore[index]
        {"value": "original"},
    )

    with pytest.raises(TypeError):
        frozen["unexpected"] = True
    with pytest.raises(TypeError):
        frozen["nestedObject"]["label"] = "changed"  # type: ignore[index]
    with pytest.raises(TypeError):
        frozen["nestedObject"]["nestedArray"][0] = "changed"  # type: ignore[index]
    with pytest.raises(AttributeError):
        frozen["nestedObject"]["nestedArray"].append("changed")  # type: ignore[attr-defined,index]


def test_condition_definition_collections_are_isolated_between_dto_instances() -> None:
    payload = _valid_request()
    condition = payload["ruleVersions"][0]["conditionDefinition"]  # type: ignore[index]
    condition["nestedObject"] = {"nestedArray": [{"value": "original"}]}  # type: ignore[index]

    first = RuleAnalysisRequest.model_validate(payload).rule_versions[0].condition_definition
    second = RuleAnalysisRequest.model_validate(payload).rule_versions[0].condition_definition

    assert first is not second
    assert first["nestedObject"] is not second["nestedObject"]
    assert (
        first["nestedObject"]["nestedArray"]
        is not second["nestedObject"][  # type: ignore[index]
            "nestedArray"
        ]
    )
    assert (
        first["nestedObject"]["nestedArray"][0]
        is not second["nestedObject"][  # type: ignore[index]
            "nestedArray"
        ][0]
    )


def test_frozen_condition_definition_serializes_as_alias_json_and_round_trips() -> None:
    original = _valid_request()
    condition = original["ruleVersions"][0]["conditionDefinition"]  # type: ignore[index]
    condition["nestedObject"] = {"nestedArray": [{"value": "original"}]}  # type: ignore[index]
    request = RuleAnalysisRequest.model_validate(original)

    dumped = request.model_dump(by_alias=True, mode="json")
    serialized = json.loads(request.model_dump_json())
    rendered_condition = dumped["ruleVersions"][0]["conditionDefinition"]
    reparsed = RuleAnalysisRequest.model_validate(dumped)

    assert dumped == original
    assert serialized == original
    assert isinstance(rendered_condition, dict)
    assert isinstance(rendered_condition["nestedObject"], dict)
    assert isinstance(rendered_condition["nestedObject"]["nestedArray"], list)
    assert reparsed.model_dump(by_alias=True, mode="json") == original


def test_condition_definition_must_be_a_wire_object() -> None:
    payload = _valid_request()
    payload["ruleVersions"][0]["conditionDefinition"] = []  # type: ignore[index]

    with pytest.raises(ValidationError):
        RuleAnalysisRequest.model_validate(payload)


@pytest.mark.parametrize(
    ("model_cls", "payload", "alias", "snake_name", "strict_field", "strict_value"),
    _nested_dto_cases(),
)
def test_every_concrete_dto_enforces_alias_extra_strict_and_frozen_policies(
    model_cls: type[BaseModel],
    payload: dict[str, object],
    alias: str,
    snake_name: str,
    strict_field: str,
    strict_value: object,
) -> None:
    valid = deepcopy(payload)
    model = model_cls.model_validate(valid)
    assert model_cls.model_config["validate_by_alias"] is True
    assert model_cls.model_config["validate_by_name"] is False
    assert model_cls.model_config["serialize_by_alias"] is True
    assert model_cls.model_config["extra"] == "forbid"
    assert model_cls.model_config["frozen"] is True
    assert model_cls.model_config["strict"] is True

    snake_payload = deepcopy(payload)
    snake_payload[snake_name] = snake_payload.pop(alias)
    with pytest.raises(ValidationError):
        model_cls.model_validate(snake_payload)

    extra_payload = deepcopy(payload)
    extra_payload["unexpected"] = "value"
    with pytest.raises(ValidationError) as exc_info:
        model_cls.model_validate(extra_payload)
    assert any(error["type"] == "extra_forbidden" for error in exc_info.value.errors())

    coercion_payload = deepcopy(payload)
    coercion_payload[strict_field] = strict_value
    with pytest.raises(ValidationError):
        model_cls.model_validate(coercion_payload)

    field_name = next(iter(model_cls.model_fields))
    with pytest.raises((ValidationError, FrozenInstanceError)):
        setattr(model, field_name, getattr(model, field_name))


def test_request_json_round_trip_uses_aliases_and_preserves_wire_values() -> None:
    original = _valid_request()
    request = RuleAnalysisRequest.model_validate_json(json.dumps(original))

    rendered = json.loads(request.model_dump_json())
    reparsed = RuleAnalysisRequest.model_validate_json(request.model_dump_json())

    assert rendered == original
    assert reparsed == request


def test_business_contract_shape_can_pass_dto_wire_validation() -> None:
    payload = deepcopy(_valid_request())
    payload["transaction"]["transactionType"] = "ATM_WITHDRAWAL"  # type: ignore[index]
    payload["evaluationCutoffAt"] = "2026-07-23T12:00:01Z"
    payload["behaviorEvents"][0]["deviceRef"] = None  # type: ignore[index]
    payload["behaviorEvents"].append(deepcopy(payload["behaviorEvents"][0]))  # type: ignore[union-attr]
    payload["ruleVersions"][0]["weight"] = 14  # type: ignore[index]
    payload["ruleVersions"][0]["conditionDefinition"]["extra"] = True  # type: ignore[index]

    request = RuleAnalysisRequest.model_validate(payload)

    assert request.transaction.transaction_type is TransactionType.ATM_WITHDRAWAL
    assert request.rule_versions[0].weight == 14


@pytest.mark.parametrize("matched", [True, False])
def test_v2_matched_and_unmatched_requests_are_strict_immutable_dtos(matched: bool) -> None:
    original = _valid_v2_request(matched=matched)

    request = RuleAnalysisRequestV2.model_validate(original)
    rendered = request.model_dump(mode="json", by_alias=True)

    assert rendered == original
    assert isinstance(request.external_risk, ExternalRiskSnapshotRequest)
    assert request.external_risk.lookup_status is ExternalRiskLookupStatus.SUCCEEDED
    expected_policy = (
        ExternalRiskPolicyResult.MATCHED if matched else ExternalRiskPolicyResult.UNMATCHED
    )
    assert request.external_risk.policy_result is expected_policy
    assert isinstance(request.external_risk.matches, tuple)
    assert all(isinstance(item, ExternalRiskMatchRequest) for item in request.external_risk.matches)
    with pytest.raises(ValidationError):
        request.external_risk.provider_code = "CHANGED"


def test_v1_continues_to_reject_external_risk_as_an_unknown_field() -> None:
    with pytest.raises(ValidationError) as exc_info:
        RuleAnalysisRequest.model_validate(_valid_v2_request())

    assert any(
        error["type"] == "extra_forbidden" and error["loc"] == ("externalRisk",)
        for error in exc_info.value.errors()
    )


@pytest.mark.parametrize("mode", ["missing", "null"])
def test_v2_rejects_missing_or_null_external_risk(mode: str) -> None:
    payload = _valid_request()
    if mode == "null":
        payload["externalRisk"] = None

    with pytest.raises(ValidationError):
        RuleAnalysisRequestV2.model_validate(payload)


@pytest.mark.parametrize(
    "field",
    ["providerCode", "lookupStatus", "policyResult", "providerAsOf", "lookedUpAt", "matches"],
)
@pytest.mark.parametrize("use_null", [False, True], ids=["missing", "null"])
def test_v2_rejects_every_missing_or_null_external_risk_field(
    field: str,
    use_null: bool,
) -> None:
    payload = _valid_v2_request()
    external_risk = payload["externalRisk"]
    assert isinstance(external_risk, dict)
    if use_null:
        external_risk[field] = None
    else:
        external_risk.pop(field)

    with pytest.raises(ValidationError):
        RuleAnalysisRequestV2.model_validate(payload)


@pytest.mark.parametrize("location", ["top", "snapshot", "match"])
def test_v2_rejects_unknown_fields_at_every_new_object_level(location: str) -> None:
    payload = _valid_v2_request()
    external_risk = payload["externalRisk"]
    assert isinstance(external_risk, dict)
    if location == "top":
        payload["unexpected"] = "value"
    elif location == "snapshot":
        external_risk["unexpected"] = "value"
    else:
        matches = external_risk["matches"]
        assert isinstance(matches, list)
        matches[0]["unexpected"] = "value"

    with pytest.raises(ValidationError) as exc_info:
        RuleAnalysisRequestV2.model_validate(payload)

    assert any(error["type"] == "extra_forbidden" for error in exc_info.value.errors())


@pytest.mark.parametrize(
    ("field", "value"),
    [
        ("providerCode", " external-risk "),
        ("lookupStatus", "FAILED"),
        ("policyResult", "matched"),
        ("providerAsOf", "2026-07-23T11:59:59.1234567Z"),
        ("lookedUpAt", "2026-07-23T12:00:00+00:00"),
        ("matches", "not-an-array"),
    ],
)
def test_v2_rejects_invalid_external_risk_wire_values(field: str, value: object) -> None:
    payload = _valid_v2_request()
    external_risk = payload["externalRisk"]
    assert isinstance(external_risk, dict)
    external_risk[field] = value

    with pytest.raises(ValidationError):
        RuleAnalysisRequestV2.model_validate(payload)


@pytest.mark.parametrize(
    ("field", "value"),
    [
        ("subjectType", "CUSTOMER"),
        ("externalRiskType", "PHISHING_IP"),
        ("reasonCode", "UNKNOWN_REASON"),
    ],
)
def test_v2_rejects_unsupported_match_enums(field: str, value: str) -> None:
    payload = _valid_v2_request()
    match = payload["externalRisk"]["matches"][0]  # type: ignore[index]
    match[field] = value

    with pytest.raises(ValidationError):
        RuleAnalysisRequestV2.model_validate(payload)
