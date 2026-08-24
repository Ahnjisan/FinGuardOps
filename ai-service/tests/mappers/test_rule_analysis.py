import json
from copy import deepcopy
from datetime import UTC, datetime
from decimal import Decimal
from uuid import UUID

import pytest
from pydantic import ValidationError

from finguardops_ai.mappers.rule_analysis import (
    RuleAnalysisExecutionInput,
    RuleAnalysisRequestError,
    RuleAnalysisRequestErrorCategory,
    RuleAnalysisRequestMapper,
    RuleAnalysisResponseMapper,
)
from finguardops_ai.rules.v1 import (
    BehaviorEventSnapshot,
    BehaviorEventType,
    FraudRuleLifecycleStatus,
    RuleEvidenceTransformer,
    RuleExecutionOrchestrator,
    RuleExecutionPlanBuilder,
    RuleExecutionPlanRunner,
    RuleId,
    RuleScoringCalculator,
    RuleVersionSnapshot,
    RuleVersionStatus,
    TransactionSnapshot,
    TransactionType,
    create_default_rule_evaluator_registry,
)
from finguardops_ai.schemas.rule_analysis import (
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
                "eventId": "30000000-0000-4000-8000-000000000031",
                "eventType": "PASSWORD_CHANGED",
                "occurredAt": "2026-07-23T11:56:00Z",
                "externalCustomerRef": "customer-a",
                "accountRef": None,
                "deviceRef": None,
                "beneficiaryRef": None,
            },
            {
                "eventId": "30000000-0000-4000-8000-000000000032",
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


def _external_match(
    subject_type: str,
    external_risk_type: str,
    reason_code: str,
) -> dict[str, object]:
    return {
        "subjectType": subject_type,
        "externalRiskType": external_risk_type,
        "reasonCode": reason_code,
    }


def _valid_external_risk() -> dict[str, object]:
    return {
        "providerCode": "EXTERNAL_RISK_MOCK_V1",
        "lookupStatus": "SUCCEEDED",
        "policyResult": "MATCHED",
        "providerAsOf": "2026-07-23T11:59:59.123456Z",
        "lookedUpAt": "2026-07-23T12:00:00.654321Z",
        "matches": [
            _external_match(
                "SENDER_ACCOUNT",
                "SUSPICIOUS_ACCOUNT",
                "SUSPICIOUS_SENDER_ACCOUNT",
            )
        ],
    }


def _valid_v2_request() -> dict[str, object]:
    payload = _valid_request()
    payload["externalRisk"] = _valid_external_risk()
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


def _map(payload: dict[str, object]):
    dto = RuleAnalysisRequest.model_validate(payload)
    return RuleAnalysisRequestMapper.to_domain(dto)


def _map_v2(payload: dict[str, object]):
    dto = RuleAnalysisRequestV2.model_validate(payload)
    return RuleAnalysisRequestMapper.to_domain(dto)


def _analysis_result():
    mapped = _map(_valid_request())
    registry = create_default_rule_evaluator_registry()
    plan = RuleExecutionPlanBuilder(registry).build(
        mapped.evaluation_cutoff_at,
        mapped.rule_versions,
    )
    planned_results = RuleExecutionPlanRunner(RuleExecutionOrchestrator(registry)).execute(
        plan, mapped.rule_input
    )
    scoring_result = RuleScoringCalculator.calculate(plan, planned_results)
    return RuleEvidenceTransformer.transform(plan, planned_results, scoring_result)


def test_request_mapper_preserves_every_domain_input_field_and_order() -> None:
    dto = RuleAnalysisRequest.model_validate(_valid_request())
    mapped = RuleAnalysisRequestMapper.to_domain(dto)

    assert isinstance(mapped, RuleAnalysisExecutionInput)
    assert mapped.evaluation_cutoff_at == datetime(2026, 7, 23, 12, 0, tzinfo=UTC)
    assert mapped.rule_input.transaction == TransactionSnapshot(
        transaction_id=UUID("10000000-0000-4000-8000-000000000001"),
        transaction_type=TransactionType.ACCOUNT_TRANSFER,
        amount=Decimal("12000000"),
        currency_code="KRW",
        occurred_at=datetime(2026, 7, 23, 12, 0, tzinfo=UTC),
        external_customer_ref="customer-a",
        sender_account_ref="sender-a",
        recipient_account_ref="recipient-a",
        device_ref="device-a",
    )
    assert mapped.rule_input.behavior_events == (
        BehaviorEventSnapshot(
            event_id=UUID("30000000-0000-4000-8000-000000000001"),
            event_type=BehaviorEventType.DEVICE_REGISTERED,
            occurred_at=datetime(2026, 7, 23, 11, 55, tzinfo=UTC),
            external_customer_ref="customer-a",
            account_ref=None,
            device_ref="device-a",
            beneficiary_ref=None,
        ),
        BehaviorEventSnapshot(
            event_id=UUID("30000000-0000-4000-8000-000000000031"),
            event_type=BehaviorEventType.PASSWORD_CHANGED,
            occurred_at=datetime(2026, 7, 23, 11, 56, tzinfo=UTC),
            external_customer_ref="customer-a",
            account_ref=None,
            device_ref=None,
            beneficiary_ref=None,
        ),
        BehaviorEventSnapshot(
            event_id=UUID("30000000-0000-4000-8000-000000000032"),
            event_type=BehaviorEventType.TRANSFER_LIMIT_CHANGED,
            occurred_at=datetime(2026, 7, 23, 11, 57, tzinfo=UTC),
            external_customer_ref="customer-a",
            account_ref="sender-a",
            device_ref=None,
            beneficiary_ref=None,
        ),
        BehaviorEventSnapshot(
            event_id=UUID("30000000-0000-4000-8000-000000000004"),
            event_type=BehaviorEventType.BENEFICIARY_REGISTERED,
            occurred_at=datetime(2026, 7, 23, 11, 59, tzinfo=UTC),
            external_customer_ref="customer-a",
            account_ref="sender-a",
            device_ref=None,
            beneficiary_ref="recipient-a",
        ),
    )
    assert mapped.rule_versions == (
        RuleVersionSnapshot(
            fraud_rule_id=UUID("10000000-0000-4000-8000-000000000011"),
            rule_code="TRANSFER_ABSOLUTE_HIGH_AMOUNT",
            lifecycle_status=FraudRuleLifecycleStatus.ACTIVE,
            rule_version_id=UUID("20000000-0000-4000-8000-000000000001"),
            version_number=1,
            status=RuleVersionStatus.PUBLISHED,
            reason_code="TRANSFER_ABSOLUTE_HIGH_AMOUNT",
            weight=15,
            condition_definition={
                "transactionTypes": ["ACCOUNT_TRANSFER", "OPEN_BANKING_TRANSFER"],
                "currencyCode": "KRW",
                "amountThreshold": "10000000",
            },
            effective_from=datetime(2026, 7, 22, 12, 0, tzinfo=UTC),
            effective_to=None,
        ),
        RuleVersionSnapshot(
            fraud_rule_id=UUID("10000000-0000-4000-8000-000000000012"),
            rule_code="RECENT_DEVICE_REGISTRATION_HIGH_AMOUNT",
            lifecycle_status=FraudRuleLifecycleStatus.ACTIVE,
            rule_version_id=UUID("20000000-0000-4000-8000-000000000002"),
            version_number=2,
            status=RuleVersionStatus.PUBLISHED,
            reason_code="RECENT_DEVICE_REGISTRATION_HIGH_AMOUNT",
            weight=20,
            condition_definition={
                "prerequisiteRuleCode": "TRANSFER_ABSOLUTE_HIGH_AMOUNT",
                "eventType": "DEVICE_REGISTERED",
                "windowSeconds": 86400,
                "matchPolicy": "SAME_CUSTOMER_AND_DEVICE",
                "selectionPolicy": "LATEST_OCCURRED_AT_THEN_EVENT_ID_ASC",
            },
            effective_from=datetime(2026, 7, 22, 12, 0, tzinfo=UTC),
            effective_to=None,
        ),
        RuleVersionSnapshot(
            fraud_rule_id=UUID("10000000-0000-4000-8000-000000000013"),
            rule_code="RECENT_SECURITY_CHANGE_HIGH_AMOUNT",
            lifecycle_status=FraudRuleLifecycleStatus.ACTIVE,
            rule_version_id=UUID("20000000-0000-4000-8000-000000000003"),
            version_number=3,
            status=RuleVersionStatus.PUBLISHED,
            reason_code="RECENT_SECURITY_CHANGE_HIGH_AMOUNT",
            weight=40,
            condition_definition={
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
            effective_from=datetime(2026, 7, 22, 12, 0, tzinfo=UTC),
            effective_to=None,
        ),
        RuleVersionSnapshot(
            fraud_rule_id=UUID("10000000-0000-4000-8000-000000000014"),
            rule_code="RECENT_BENEFICIARY_TRANSFER",
            lifecycle_status=FraudRuleLifecycleStatus.ACTIVE,
            rule_version_id=UUID("20000000-0000-4000-8000-000000000004"),
            version_number=4,
            status=RuleVersionStatus.PUBLISHED,
            reason_code="RECENT_BENEFICIARY_TRANSFER",
            weight=10,
            condition_definition={
                "eventType": "BENEFICIARY_REGISTERED",
                "windowSeconds": 86400,
                "matchPolicy": "SAME_CUSTOMER_SENDER_ACCOUNT_AND_BENEFICIARY",
                "selectionPolicy": "LATEST_OCCURRED_AT_THEN_EVENT_ID_ASC",
            },
            effective_from=datetime(2026, 7, 22, 12, 0, tzinfo=UTC),
            effective_to=None,
        ),
    )

    for request_rule, domain_rule in zip(dto.rule_versions, mapped.rule_versions, strict=True):
        assert request_rule.fraud_rule_id == domain_rule.fraud_rule_id
        assert request_rule.rule_version_id == domain_rule.rule_version_id
        assert request_rule.condition_definition is not domain_rule.condition_definition
    assert (
        dto.rule_versions[0].condition_definition["transactionTypes"]
        is not mapped.rule_versions[0].condition_definition["transactionTypes"]
    )


def test_domain_condition_definition_is_independent_from_frozen_dto() -> None:
    dto = RuleAnalysisRequest.model_validate(_valid_request())
    mapped = RuleAnalysisRequestMapper.to_domain(dto)
    dto_condition = dto.rule_versions[0].condition_definition
    domain_condition = mapped.rule_versions[0].condition_definition

    with pytest.raises(TypeError):
        dto_condition["amountThreshold"] = "9999999"
    with pytest.raises(TypeError):
        dto_condition["transactionTypes"][0] = "ATM_WITHDRAWAL"  # type: ignore[index]

    domain_condition["amountThreshold"] = "9999999"
    domain_condition["transactionTypes"].append("ATM_WITHDRAWAL")  # type: ignore[union-attr]

    assert dto_condition["amountThreshold"] == "10000000"
    assert dto_condition["transactionTypes"] == (
        "ACCOUNT_TRANSFER",
        "OPEN_BANKING_TRANSFER",
    )


@pytest.mark.parametrize(
    ("path", "value"),
    [
        (("transaction", "transactionType"), "ATM_WITHDRAWAL"),
        (("evaluationCutoffAt",), "2026-07-23T12:00:01Z"),
        (("behaviorEvents", 0, "beneficiaryRef"), "forbidden"),
        (("ruleVersions", 0, "lifecycleStatus"), "RETIRED"),
        (("ruleVersions", 0, "status"), "DRAFT"),
    ],
)
def test_wire_valid_business_contract_failures_use_dedicated_error(
    path: tuple[object, ...],
    value: object,
) -> None:
    payload = _valid_request()
    _replace(payload, path, value)
    dto = RuleAnalysisRequest.model_validate(payload)

    with pytest.raises(RuleAnalysisRequestError) as exc_info:
        RuleAnalysisRequestMapper.to_domain(dto)

    assert exc_info.value.category is RuleAnalysisRequestErrorCategory.RULE_CONTRACT_ERROR
    assert not isinstance(exc_info.value, ValidationError)


@pytest.mark.parametrize(
    ("rule_index", "field", "value"),
    [
        pytest.param(0, "weight", 14, id="R001-weight"),
        pytest.param(0, "reasonCode", "WRONG_REASON", id="R001-reasonCode"),
        pytest.param(1, "weight", 19, id="R002-weight"),
        pytest.param(1, "reasonCode", "WRONG_REASON", id="R002-reasonCode"),
        pytest.param(2, "weight", 39, id="R003-weight"),
        pytest.param(2, "reasonCode", "WRONG_REASON", id="R003-reasonCode"),
        pytest.param(3, "weight", 9, id="R004-weight"),
        pytest.param(3, "reasonCode", "WRONG_REASON", id="R004-reasonCode"),
    ],
)
def test_each_rule_rejects_noncanonical_weight_and_reason_code_after_dto(
    rule_index: int,
    field: str,
    value: object,
) -> None:
    payload = _valid_request()
    payload["ruleVersions"][rule_index][field] = value  # type: ignore[index]
    dto = RuleAnalysisRequest.model_validate(payload)

    with pytest.raises(RuleAnalysisRequestError) as exc_info:
        RuleAnalysisRequestMapper.to_domain(dto)

    assert exc_info.value.category is RuleAnalysisRequestErrorCategory.RULE_CONTRACT_ERROR
    assert not isinstance(exc_info.value, ValidationError)


def test_duplicate_event_id_is_a_post_dto_contract_error() -> None:
    payload = _valid_request()
    duplicate = deepcopy(payload["behaviorEvents"][0])  # type: ignore[index]
    payload["behaviorEvents"].append(duplicate)  # type: ignore[union-attr]
    dto = RuleAnalysisRequest.model_validate(payload)

    with pytest.raises(RuleAnalysisRequestError, match="duplicate eventId"):
        RuleAnalysisRequestMapper.to_domain(dto)


def test_dependency_violation_is_a_post_dto_contract_error() -> None:
    payload = _valid_request()
    payload["ruleVersions"] = [payload["ruleVersions"][2]]  # type: ignore[index]
    dto = RuleAnalysisRequest.model_validate(payload)

    with pytest.raises(RuleAnalysisRequestError, match="R003 requires R001"):
        RuleAnalysisRequestMapper.to_domain(dto)


def test_condition_definition_violation_is_a_post_dto_contract_error() -> None:
    payload = _valid_request()
    payload["ruleVersions"][0]["conditionDefinition"]["amountThreshold"] = "9999999"  # type: ignore[index]
    dto = RuleAnalysisRequest.model_validate(payload)

    with pytest.raises(RuleAnalysisRequestError, match="conditionDefinition") as exc_info:
        RuleAnalysisRequestMapper.to_domain(dto)

    assert exc_info.value.category is RuleAnalysisRequestErrorCategory.RULE_CONTRACT_ERROR


def test_rule_version_duplicates_and_effective_period_are_post_dto_errors() -> None:
    duplicate_payload = _valid_request()
    duplicate_payload["ruleVersions"][1]["ruleVersionId"] = (  # type: ignore[index]
        duplicate_payload["ruleVersions"][0]["ruleVersionId"]  # type: ignore[index]
    )
    period_payload = _valid_request()
    period_payload["ruleVersions"][0]["effectiveFrom"] = "2026-07-24T12:00:00Z"  # type: ignore[index]

    with pytest.raises(RuleAnalysisRequestError, match="duplicate ruleVersionId"):
        _map(duplicate_payload)
    with pytest.raises(RuleAnalysisRequestError, match="not yet effective"):
        _map(period_payload)


def test_response_mapper_creates_official_success_envelope_and_alias_json() -> None:
    response = RuleAnalysisResponseMapper.to_dto(
        UUID("10000000-0000-4000-8000-000000000001"),
        "trace_demo_rule_0001",
        _analysis_result(),
    )
    payload = json.loads(response.model_dump_json())

    assert set(payload) == {"transactionId", "traceId", "analysis"}
    assert payload["transactionId"] == "10000000-0000-4000-8000-000000000001"
    assert payload["traceId"] == "trace_demo_rule_0001"
    assert payload["analysis"]["evaluationCutoffAt"] == "2026-07-23T12:00:00Z"
    assert len(payload["analysis"]["ruleSetVersion"]) == 64
    assert payload["analysis"]["scoringResult"] == {
        "scoringPolicyVersion": "scoring-policy-v1",
        "riskScore": 75,
        "riskLevel": "HIGH",
        "ruleContributions": [
            {
                "ruleId": "R001",
                "executionOrder": 1,
                "matched": True,
                "originalContribution": 15,
            },
            {
                "ruleId": "R002",
                "executionOrder": 2,
                "matched": True,
                "originalContribution": 20,
            },
            {
                "ruleId": "R003",
                "executionOrder": 3,
                "matched": True,
                "originalContribution": 40,
            },
            {
                "ruleId": "R004",
                "executionOrder": 4,
                "matched": True,
                "originalContribution": 10,
            },
        ],
        "groupSummaries": [
            {
                "groupId": "amount",
                "rawScore": 15,
                "cap": 15,
                "appliedScore": 15,
                "reduction": 0,
            },
            {
                "groupId": "security",
                "rawScore": 70,
                "cap": 60,
                "appliedScore": 60,
                "reduction": 10,
            },
        ],
    }
    assert payload["analysis"]["evidence"] == [
        {
            "ruleVersionId": "20000000-0000-4000-8000-000000000001",
            "ruleCode": "TRANSFER_ABSOLUTE_HIGH_AMOUNT",
            "ruleVersion": "1",
            "reasonCode": "TRANSFER_ABSOLUTE_HIGH_AMOUNT",
            "executionOrder": 1,
            "scoreContribution": 15,
            "evidenceOccurredAt": "2026-07-23T12:00:00Z",
            "ruleId": "R001",
            "observationSummary": {
                "observedAmount": "12000000",
                "amountThreshold": "10000000",
            },
        },
        {
            "ruleVersionId": "20000000-0000-4000-8000-000000000002",
            "ruleCode": "RECENT_DEVICE_REGISTRATION_HIGH_AMOUNT",
            "ruleVersion": "2",
            "reasonCode": "RECENT_DEVICE_REGISTRATION_HIGH_AMOUNT",
            "executionOrder": 2,
            "scoreContribution": 20,
            "evidenceOccurredAt": "2026-07-23T11:55:00Z",
            "ruleId": "R002",
            "observationSummary": {
                "observedAmount": "12000000",
                "amountThreshold": "10000000",
                "eventId": "30000000-0000-4000-8000-000000000001",
                "deviceRegisteredAt": "2026-07-23T11:55:00Z",
                "elapsedSeconds": 300,
                "windowSeconds": 86400,
            },
        },
        {
            "ruleVersionId": "20000000-0000-4000-8000-000000000003",
            "ruleCode": "RECENT_SECURITY_CHANGE_HIGH_AMOUNT",
            "ruleVersion": "3",
            "reasonCode": "RECENT_SECURITY_CHANGE_HIGH_AMOUNT",
            "executionOrder": 3,
            "scoreContribution": 40,
            "evidenceOccurredAt": "2026-07-23T11:57:00Z",
            "ruleId": "R003",
            "observationSummary": {
                "observedAmount": "12000000",
                "amountThreshold": "10000000",
                "passwordChangedEventId": "30000000-0000-4000-8000-000000000031",
                "passwordChangedAt": "2026-07-23T11:56:00Z",
                "transferLimitChangedEventId": "30000000-0000-4000-8000-000000000032",
                "transferLimitChangedAt": "2026-07-23T11:57:00Z",
                "elapsedSeconds": 180,
                "windowSeconds": 86400,
            },
        },
        {
            "ruleVersionId": "20000000-0000-4000-8000-000000000004",
            "ruleCode": "RECENT_BENEFICIARY_TRANSFER",
            "ruleVersion": "4",
            "reasonCode": "RECENT_BENEFICIARY_TRANSFER",
            "executionOrder": 4,
            "scoreContribution": 10,
            "evidenceOccurredAt": "2026-07-23T11:59:00Z",
            "ruleId": "R004",
            "observationSummary": {
                "observedAmount": "12000000",
                "eventId": "30000000-0000-4000-8000-000000000004",
                "beneficiaryRegisteredAt": "2026-07-23T11:59:00Z",
                "elapsedSeconds": 60,
                "windowSeconds": 86400,
            },
        },
    ]
    assert all("fraudRuleId" not in item for item in payload["analysis"]["evidence"])
    assert tuple(type(item) for item in response.analysis.evidence) == (
        R001EvidenceResponse,
        R002EvidenceResponse,
        R003EvidenceResponse,
        R004EvidenceResponse,
    )
    assert tuple(type(item.observation_summary) for item in response.analysis.evidence) == (
        R001ObservationResponse,
        R002ObservationResponse,
        R003ObservationResponse,
        R004ObservationResponse,
    )


def test_response_mapper_does_not_recalculate_scoring_or_evidence(monkeypatch) -> None:
    analysis_result = _analysis_result()

    def fail(*_args, **_kwargs):
        raise AssertionError("calculation must not be called by the response mapper")

    monkeypatch.setattr(RuleScoringCalculator, "calculate", fail)
    monkeypatch.setattr(RuleEvidenceTransformer, "transform", fail)

    response = RuleAnalysisResponseMapper.to_dto(
        UUID("10000000-0000-4000-8000-000000000001"),
        "trace_demo_rule_0001",
        analysis_result,
    )

    assert response.analysis.scoring_result.risk_score == 75
    assert tuple(item.rule_id for item in response.analysis.evidence) == (
        RuleId.R001,
        RuleId.R002,
        RuleId.R003,
        RuleId.R004,
    )


def test_response_json_round_trip_preserves_full_envelope() -> None:
    response = RuleAnalysisResponseMapper.to_dto(
        UUID("10000000-0000-4000-8000-000000000001"),
        "trace_demo_rule_0001",
        _analysis_result(),
    )

    reparsed = RuleAnalysisResponse.model_validate_json(response.model_dump_json())

    assert reparsed == response


def test_response_mapper_requires_explicit_valid_transaction_and_trace_ids() -> None:
    analysis_result = _analysis_result()

    with pytest.raises(ValidationError):
        RuleAnalysisResponseMapper.to_dto(
            UUID("10000000-0000-1000-8000-000000000001"),
            "trace_demo_rule_0001",
            analysis_result,
        )
    with pytest.raises(ValidationError):
        RuleAnalysisResponseMapper.to_dto(
            UUID("10000000-0000-4000-8000-000000000001"),
            "short",
            analysis_result,
        )


def test_v1_and_v2_map_to_the_exact_same_rule_execution_input() -> None:
    v1_input = _map(_valid_request())
    v2_input = _map_v2(_valid_v2_request())

    assert v2_input == v1_input
    assert not hasattr(v2_input.rule_input, "external_risk")


@pytest.mark.parametrize(
    "matches",
    [
        [],
        [
            _external_match(
                "SENDER_ACCOUNT",
                "SUSPICIOUS_ACCOUNT",
                "SUSPICIOUS_SENDER_ACCOUNT",
            )
        ],
        [
            _external_match(
                "SENDER_ACCOUNT",
                "SUSPICIOUS_ACCOUNT",
                "SUSPICIOUS_SENDER_ACCOUNT",
            ),
            _external_match(
                "RECIPIENT_ACCOUNT",
                "SUSPICIOUS_ACCOUNT",
                "SUSPICIOUS_RECIPIENT_ACCOUNT",
            ),
            _external_match("DEVICE", "RISK_DEVICE", "RISK_DEVICE"),
        ],
    ],
    ids=["unmatched", "one-match", "three-matches"],
)
def test_v2_accepts_only_supported_canonical_match_sets(
    matches: list[dict[str, object]],
) -> None:
    payload = _valid_v2_request()
    external_risk = payload["externalRisk"]
    assert isinstance(external_risk, dict)
    external_risk["matches"] = matches
    external_risk["policyResult"] = "UNMATCHED" if not matches else "MATCHED"

    mapped = _map_v2(payload)

    assert mapped == _map(_valid_request())


def _invalid_external_risk_cases() -> list[object]:
    sender = _external_match(
        "SENDER_ACCOUNT",
        "SUSPICIOUS_ACCOUNT",
        "SUSPICIOUS_SENDER_ACCOUNT",
    )
    recipient = _external_match(
        "RECIPIENT_ACCOUNT",
        "SUSPICIOUS_ACCOUNT",
        "SUSPICIOUS_RECIPIENT_ACCOUNT",
    )
    device = _external_match("DEVICE", "RISK_DEVICE", "RISK_DEVICE")
    return [
        pytest.param("MATCHED", [], None, None, id="matched-empty"),
        pytest.param("UNMATCHED", [sender], None, None, id="unmatched-nonempty"),
        pytest.param("MATCHED", [sender, recipient, device, sender], None, None, id="four"),
        pytest.param(
            "MATCHED",
            [_external_match("SENDER_ACCOUNT", "RISK_DEVICE", "RISK_DEVICE")],
            None,
            None,
            id="unsupported-combination",
        ),
        pytest.param("MATCHED", [sender, sender], None, None, id="duplicate"),
        pytest.param("MATCHED", [device, sender], None, None, id="noncanonical"),
        pytest.param(
            "MATCHED",
            [sender],
            "2026-07-23T12:00:00.000001Z",
            None,
            id="provider-after-cutoff",
        ),
        pytest.param(
            "MATCHED",
            [sender],
            None,
            "2026-07-23T11:59:59.999999Z",
            id="lookup-before-cutoff",
        ),
    ]


@pytest.mark.parametrize(
    ("policy_result", "matches", "provider_as_of", "looked_up_at"),
    _invalid_external_risk_cases(),
)
def test_v2_cross_field_contract_failures_use_dedicated_error(
    policy_result: str,
    matches: list[dict[str, object]],
    provider_as_of: str | None,
    looked_up_at: str | None,
) -> None:
    payload = _valid_v2_request()
    external_risk = payload["externalRisk"]
    assert isinstance(external_risk, dict)
    external_risk["policyResult"] = policy_result
    external_risk["matches"] = matches
    if provider_as_of is not None:
        external_risk["providerAsOf"] = provider_as_of
    if looked_up_at is not None:
        external_risk["lookedUpAt"] = looked_up_at
    dto = RuleAnalysisRequestV2.model_validate(payload)

    with pytest.raises(RuleAnalysisRequestError) as exc_info:
        RuleAnalysisRequestMapper.to_domain(dto)

    assert exc_info.value.category is RuleAnalysisRequestErrorCategory.RULE_CONTRACT_ERROR
