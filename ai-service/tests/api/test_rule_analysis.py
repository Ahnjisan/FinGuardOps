import asyncio
import json
import re
from collections.abc import Iterator, Mapping
from types import MappingProxyType
from uuid import UUID

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

import finguardops_ai.rules.v1.execution_plan as execution_plan_module
from finguardops_ai.api.middleware import (
    MAX_RULE_ANALYSIS_BODY_BYTES,
    RuleAnalysisHttpMiddleware,
)
from finguardops_ai.main import create_app
from finguardops_ai.rules.v1 import (
    RuleEvaluatorRegistry,
    RuleEvidenceError,
    RuleEvidenceErrorCategory,
    RuleEvidenceTransformer,
    RuleExecutionOrchestrator,
    RuleExecutionPlanBuilder,
    RuleExecutionPlanError,
    RuleExecutionPlanErrorCategory,
    RuleExecutionPlanRunner,
    RuleExecutionPlanRunnerError,
    RuleExecutionPlanRunnerErrorCategory,
    RuleId,
    RuleScoringCalculator,
    RuleScoringError,
    RuleScoringErrorCategory,
)
from finguardops_ai.rules.v1.execution_plan import RuleExecutionPlanErrorOrigin
from finguardops_ai.services.rule_analysis import (
    RuleAnalysisService,
    get_rule_analysis_service,
)

ENDPOINT = "/api/v1/rule-analysis"
V2_ENDPOINT = "/api/v2/rule-analysis"
TRACE_ID = "trace_rule_endpoint_0001"
TRACE_PATTERN = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")


def _valid_request() -> dict[str, object]:
    return {
        "evaluationCutoffAt": "2026-07-23T12:00:00Z",
        "transaction": {
            "transactionId": "10000000-0000-4000-8000-000000000001",
            "transactionType": "ACCOUNT_TRANSFER",
            "amount": "12000000",
            "currencyCode": "KRW",
            "occurredAt": "2026-07-23T12:00:00Z",
            "externalCustomerRef": "customer-ref-001",
            "senderAccountRef": "sender-account-ref-001",
            "recipientAccountRef": "recipient-account-ref-001",
            "deviceRef": "device-ref-001",
        },
        "behaviorEvents": [
            {
                "eventId": "30000000-0000-4000-8000-000000000004",
                "eventType": "BENEFICIARY_REGISTERED",
                "occurredAt": "2026-07-23T11:59:00Z",
                "externalCustomerRef": "customer-ref-001",
                "accountRef": "sender-account-ref-001",
                "deviceRef": None,
                "beneficiaryRef": "recipient-account-ref-001",
            }
        ],
        "ruleVersions": [
            {
                "fraudRuleId": "40000000-0000-4000-8000-000000000004",
                "ruleCode": "RECENT_BENEFICIARY_TRANSFER",
                "lifecycleStatus": "ACTIVE",
                "ruleVersionId": "20000000-0000-4000-8000-000000000004",
                "versionNumber": 1,
                "status": "PUBLISHED",
                "reasonCode": "RECENT_BENEFICIARY_TRANSFER",
                "weight": 10,
                "conditionDefinition": {
                    "eventType": "BENEFICIARY_REGISTERED",
                    "windowSeconds": 86400,
                    "matchPolicy": "SAME_CUSTOMER_SENDER_ACCOUNT_AND_BENEFICIARY",
                    "selectionPolicy": "LATEST_OCCURRED_AT_THEN_EVENT_ID_ASC",
                },
                "effectiveFrom": "2026-07-01T00:00:00Z",
                "effectiveTo": None,
            }
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


def _valid_external_risk(*, matched: bool = True) -> dict[str, object]:
    return {
        "providerCode": "EXTERNAL_RISK_MOCK_V1",
        "lookupStatus": "SUCCEEDED",
        "policyResult": "MATCHED" if matched else "UNMATCHED",
        "providerAsOf": "2026-07-23T11:59:59.123456Z",
        "lookedUpAt": "2026-07-23T12:00:00.654321Z",
        "matches": [
            _external_match(
                "SENDER_ACCOUNT",
                "SUSPICIOUS_ACCOUNT",
                "SUSPICIOUS_SENDER_ACCOUNT",
            )
        ]
        if matched
        else [],
    }


def _valid_v2_request(*, matched: bool = True) -> dict[str, object]:
    payload = _valid_request()
    payload["externalRisk"] = _valid_external_risk(matched=matched)
    return payload


@pytest.fixture
def application() -> FastAPI:
    return create_app()


@pytest.fixture
def client(application: FastAPI) -> Iterator[TestClient]:
    with TestClient(application, raise_server_exceptions=False) as test_client:
        yield test_client


def _post(
    client: TestClient,
    payload: object,
    trace_id: str = TRACE_ID,
    *,
    endpoint: str = ENDPOINT,
):
    return client.post(endpoint, json=payload, headers={"X-Trace-Id": trace_id})


def _assert_error_envelope(response, status_code: int, code: str, trace_id: str) -> None:
    assert response.status_code == status_code
    assert response.headers["X-Trace-Id"] == trace_id
    body = response.json()
    assert set(body) == {"code", "message", "traceId", "fieldErrors"}
    assert body["code"] == code
    assert body["traceId"] == trace_id
    assert isinstance(body["message"], str) and body["message"]
    assert isinstance(body["fieldErrors"], list)


def test_rule_analysis_returns_existing_rule_v1_result_and_trace(client: TestClient) -> None:
    response = _post(client, _valid_request())

    assert response.status_code == 200
    assert response.headers["X-Trace-Id"] == TRACE_ID
    body = response.json()
    assert body["transactionId"] == "10000000-0000-4000-8000-000000000001"
    assert body["traceId"] == TRACE_ID
    assert body["analysis"]["evaluationCutoffAt"] == "2026-07-23T12:00:00Z"
    assert body["analysis"]["scoringResult"]["riskScore"] == 10
    assert body["analysis"]["scoringResult"]["riskLevel"] == "LOW"
    assert body["analysis"]["scoringResult"]["ruleContributions"] == [
        {
            "ruleId": "R004",
            "executionOrder": 1,
            "matched": True,
            "originalContribution": 10,
        }
    ]
    assert body["analysis"]["evidence"][0]["ruleId"] == "R004"
    assert body["analysis"]["evidence"][0]["observationSummary"] == {
        "observedAmount": "12000000",
        "eventId": "30000000-0000-4000-8000-000000000004",
        "beneficiaryRegisteredAt": "2026-07-23T11:59:00Z",
        "elapsedSeconds": 60,
        "windowSeconds": 86400,
    }


def test_all_rules_unmatched_is_a_valid_low_zero_result(client: TestClient) -> None:
    payload = _valid_request()
    payload["behaviorEvents"] = []

    response = _post(client, payload)

    assert response.status_code == 200
    scoring = response.json()["analysis"]["scoringResult"]
    assert scoring["riskScore"] == 0
    assert scoring["riskLevel"] == "LOW"
    assert scoring["ruleContributions"][0]["matched"] is False
    assert scoring["ruleContributions"][0]["originalContribution"] == 0
    assert response.json()["analysis"]["evidence"] == []


@pytest.mark.parametrize("matched", [True, False])
def test_v2_matched_and_unmatched_reuse_the_exact_v1_result(
    client: TestClient,
    matched: bool,
) -> None:
    v1_response = _post(client, _valid_request())
    v2_payload = _valid_v2_request(matched=matched)
    v2_response = _post(client, v2_payload, endpoint=V2_ENDPOINT)

    assert v1_response.status_code == 200
    assert v2_response.status_code == 200
    assert v2_response.json() == v1_response.json()
    assert v2_response.headers["X-Trace-Id"] == TRACE_ID
    assert "externalRisk" not in v2_response.json()
    assert "EXTERNAL_RISK_MOCK_V1" not in v2_response.text


def test_v2_external_risk_match_kind_does_not_change_rule_result(client: TestClient) -> None:
    sender_payload = _valid_v2_request()
    device_payload = _valid_v2_request()
    device_payload["externalRisk"]["matches"] = [  # type: ignore[index]
        _external_match("DEVICE", "RISK_DEVICE", "RISK_DEVICE")
    ]

    sender_response = _post(client, sender_payload, endpoint=V2_ENDPOINT)
    device_response = _post(client, device_payload, endpoint=V2_ENDPOINT)

    assert sender_response.status_code == 200
    assert device_response.status_code == 200
    assert sender_response.json() == device_response.json()


def test_v1_continues_to_reject_external_risk(client: TestClient) -> None:
    response = _post(client, _valid_v2_request())

    _assert_error_envelope(response, 400, "INVALID_REQUEST", TRACE_ID)
    assert response.json()["fieldErrors"][0]["field"] == "body"
    assert "analysis" not in response.json()


def _invalid_v2_wire_payloads() -> list[object]:
    missing = _valid_request()
    null_snapshot = _valid_v2_request()
    null_snapshot["externalRisk"] = None
    unknown = _valid_v2_request()
    unknown["externalRisk"]["provider-secret"] = "provider-value-secret"  # type: ignore[index]
    invalid_provider = _valid_v2_request()
    invalid_provider["externalRisk"]["providerCode"] = "invalid-provider"  # type: ignore[index]
    invalid_enum = _valid_v2_request()
    invalid_enum["externalRisk"]["policyResult"] = "DEFAULT"  # type: ignore[index]
    invalid_time = _valid_v2_request()
    invalid_time["externalRisk"]["lookedUpAt"] = (  # type: ignore[index]
        "2026-07-23T12:00:00.1234567Z"
    )
    return [missing, null_snapshot, unknown, invalid_provider, invalid_enum, invalid_time]


@pytest.mark.parametrize("payload", _invalid_v2_wire_payloads())
def test_v2_wire_errors_use_400_without_calling_service(
    application: FastAPI,
    client: TestClient,
    payload: object,
    caplog: pytest.LogCaptureFixture,
) -> None:
    service = _RaisingService(AssertionError("Rule Service must not be called"))
    _override_service(application, service)

    response = _post(client, payload, endpoint=V2_ENDPOINT)

    _assert_error_envelope(response, 400, "INVALID_REQUEST", TRACE_ID)
    assert service.calls == 0
    assert "provider-secret" not in response.text
    assert "provider-value-secret" not in response.text
    assert "provider-secret" not in caplog.text
    assert "provider-value-secret" not in caplog.text


def _v2_wire_field_path_cases() -> list[tuple[object, str, str | None]]:
    missing_external_risk = _valid_request()
    invalid_provider = _valid_v2_request()
    invalid_provider["externalRisk"]["providerCode"] = "provider-secret-value"  # type: ignore[index]
    invalid_subject = _valid_v2_request()
    invalid_subject["externalRisk"]["matches"][0]["subjectType"] = (  # type: ignore[index]
        "SUBJECT_SECRET_VALUE"
    )
    return [
        (missing_external_risk, "externalRisk", None),
        (invalid_provider, "externalRisk.providerCode", "provider-secret-value"),
        (
            invalid_subject,
            "externalRisk.matches[0].subjectType",
            "SUBJECT_SECRET_VALUE",
        ),
    ]


@pytest.mark.parametrize(
    ("payload", "expected_field", "sensitive_value"),
    _v2_wire_field_path_cases(),
)
def test_v2_wire_errors_use_safe_literal_camel_case_field_paths(
    application: FastAPI,
    client: TestClient,
    payload: object,
    expected_field: str,
    sensitive_value: str | None,
    caplog: pytest.LogCaptureFixture,
) -> None:
    service = _RaisingService(AssertionError("Rule Service must not be called"))
    _override_service(application, service)

    response = _post(client, payload, endpoint=V2_ENDPOINT)

    _assert_error_envelope(response, 400, "INVALID_REQUEST", TRACE_ID)
    assert [error["field"] for error in response.json()["fieldErrors"]] == [expected_field]
    assert service.calls == 0
    if sensitive_value is not None:
        assert sensitive_value not in response.text
        assert sensitive_value not in caplog.text


def test_v2_cross_field_error_enters_service_but_stops_after_mapper(
    application: FastAPI,
    client: TestClient,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    downstream_calls = {
        "plan": 0,
        "runner": 0,
        "evaluator": 0,
        "scoring": 0,
        "evidence": 0,
    }

    def fail_if_called(stage: str):
        def fail(*args: object, **kwargs: object) -> None:
            downstream_calls[stage] += 1
            raise AssertionError(f"{stage} must not be called after mapper failure")

        return fail

    monkeypatch.setattr(RuleExecutionPlanBuilder, "build", fail_if_called("plan"))
    monkeypatch.setattr(RuleExecutionPlanRunner, "execute", fail_if_called("runner"))
    monkeypatch.setattr(RuleExecutionOrchestrator, "execute", fail_if_called("evaluator"))
    monkeypatch.setattr(RuleScoringCalculator, "calculate", fail_if_called("scoring"))
    monkeypatch.setattr(RuleEvidenceTransformer, "transform", fail_if_called("evidence"))
    service = _DelegatingService(get_rule_analysis_service())
    _override_service(application, service)
    payload = _valid_v2_request()
    payload["externalRisk"]["matches"] = []  # type: ignore[index]

    response = _post(client, payload, endpoint=V2_ENDPOINT)

    _assert_error_envelope(response, 422, "RULE_CONTRACT_ERROR", TRACE_ID)
    assert service.calls == 1
    assert downstream_calls == {
        "plan": 0,
        "runner": 0,
        "evaluator": 0,
        "scoring": 0,
        "evidence": 0,
    }
    assert "analysis" not in response.json()


@pytest.mark.parametrize("headers", [{}, {"X-Trace-Id": "invalid"}])
def test_v2_applies_existing_trace_validation(
    client: TestClient,
    headers: dict[str, str],
) -> None:
    response = client.post(V2_ENDPOINT, json=_valid_v2_request(), headers=headers)

    local_trace_id = response.headers["X-Trace-Id"]
    _assert_error_envelope(response, 400, "INVALID_REQUEST", local_trace_id)
    assert TRACE_PATTERN.fullmatch(local_trace_id)


def test_v2_applies_existing_one_mib_body_limit(client: TestClient) -> None:
    compact_json = json.dumps(_valid_v2_request(), separators=(",", ":")).encode()
    exact_body = compact_json + b" " * (MAX_RULE_ANALYSIS_BODY_BYTES - len(compact_json))
    assert len(exact_body) == MAX_RULE_ANALYSIS_BODY_BYTES

    accepted = client.post(
        V2_ENDPOINT,
        content=exact_body,
        headers={"Content-Type": "application/json", "X-Trace-Id": TRACE_ID},
    )
    rejected = client.post(
        V2_ENDPOINT,
        content=exact_body + b" ",
        headers={"Content-Type": "application/json", "X-Trace-Id": TRACE_ID},
    )

    assert accepted.status_code == 200
    _assert_error_envelope(rejected, 413, "PAYLOAD_TOO_LARGE", TRACE_ID)


@pytest.mark.parametrize(
    "headers",
    [
        {},
        {"X-Trace-Id": "short"},
        {"X-Trace-Id": " trace_rule_endpoint_0001"},
        {"X-Trace-Id": "trace_rule_endpoint_0001,trace_rule_endpoint_0002"},
    ],
)
def test_missing_or_invalid_trace_is_rejected_with_one_local_uuid(
    client: TestClient,
    headers: dict[str, str],
) -> None:
    response = client.post(ENDPOINT, json=_valid_request(), headers=headers)

    local_trace_id = response.headers["X-Trace-Id"]
    assert TRACE_PATTERN.fullmatch(local_trace_id)
    UUID(local_trace_id, version=4)
    _assert_error_envelope(response, 400, "INVALID_REQUEST", local_trace_id)
    assert response.json()["fieldErrors"] == []
    assert all(value not in response.text for value in headers.values())


def test_duplicate_raw_trace_headers_are_rejected(client: TestClient) -> None:
    response = client.post(
        ENDPOINT,
        json=_valid_request(),
        headers=[
            ("X-Trace-Id", "trace_rule_endpoint_0001"),
            ("x-trace-id", "trace_rule_endpoint_0002"),
        ],
    )

    local_trace_id = response.headers["X-Trace-Id"]
    _assert_error_envelope(response, 400, "INVALID_REQUEST", local_trace_id)
    assert TRACE_PATTERN.fullmatch(local_trace_id)
    assert "trace_rule_endpoint_0001" not in response.text
    assert "trace_rule_endpoint_0002" not in response.text


def test_malformed_json_and_wire_validation_use_400(client: TestClient) -> None:
    malformed = client.post(
        ENDPOINT,
        content=b'{"transaction":',
        headers={"Content-Type": "application/json", "X-Trace-Id": TRACE_ID},
    )
    missing_field = _post(client, {"evaluationCutoffAt": "2026-07-23T12:00:00Z"})

    _assert_error_envelope(malformed, 400, "INVALID_REQUEST", TRACE_ID)
    assert malformed.json()["fieldErrors"] == [
        {
            "field": "body",
            "code": "MALFORMED_JSON",
            "reason": "올바른 JSON 형식이어야 합니다.",
        }
    ]
    _assert_error_envelope(missing_field, 400, "INVALID_REQUEST", TRACE_ID)
    assert missing_field.json()["fieldErrors"]


def test_top_level_unknown_field_is_not_reflected(
    client: TestClient,
    caplog: pytest.LogCaptureFixture,
) -> None:
    payload = _valid_request()
    unknown_field = "customer-ref-secret-987"
    unknown_value = "account-ref-secret-654"
    payload[unknown_field] = unknown_value

    response = _post(client, payload)

    _assert_error_envelope(response, 400, "INVALID_REQUEST", TRACE_ID)
    assert response.json()["fieldErrors"] == [
        {
            "field": "body",
            "code": "INVALID_FIELD",
            "reason": "요청 필드 형식을 확인해 주세요.",
        }
    ]
    assert unknown_field not in response.text
    assert unknown_value not in response.text
    assert unknown_field not in caplog.text
    assert unknown_value not in caplog.text


def test_nested_unknown_field_is_reduced_to_safe_parent(
    client: TestClient,
    caplog: pytest.LogCaptureFixture,
) -> None:
    payload = _valid_request()
    transaction = payload["transaction"]
    assert isinstance(transaction, dict)
    unknown_field = "recipient-secret-segment"
    unknown_value = "recipient-ref-secret-321"
    transaction[unknown_field] = unknown_value

    response = _post(client, payload)

    _assert_error_envelope(response, 400, "INVALID_REQUEST", TRACE_ID)
    assert response.json()["fieldErrors"] == [
        {
            "field": "transaction",
            "code": "INVALID_FIELD",
            "reason": "요청 필드 형식을 확인해 주세요.",
        }
    ]
    assert unknown_field not in response.text
    assert unknown_value not in response.text
    assert unknown_field not in caplog.text
    assert unknown_value not in caplog.text


def test_known_wire_errors_keep_safe_camel_case_and_array_index_paths(
    client: TestClient,
) -> None:
    missing_payload = _valid_request()
    missing_transaction = missing_payload["transaction"]
    assert isinstance(missing_transaction, dict)
    missing_transaction.pop("currencyCode")

    type_payload = _valid_request()
    type_transaction = type_payload["transaction"]
    assert isinstance(type_transaction, dict)
    type_transaction["amount"] = 12000000

    array_payload = _valid_request()
    rule_versions = array_payload["ruleVersions"]
    assert isinstance(rule_versions, list)
    rule_versions[0]["weight"] = "10"

    missing_response = _post(client, missing_payload)
    type_response = _post(client, type_payload)
    array_response = _post(client, array_payload)

    assert missing_response.json()["fieldErrors"][0]["field"] == "transaction.currencyCode"
    assert type_response.json()["fieldErrors"][0]["field"] == "transaction.amount"
    assert array_response.json()["fieldErrors"][0]["field"] == "ruleVersions[0].weight"
    for response in (missing_response, type_response, array_response):
        _assert_error_envelope(response, 400, "INVALID_REQUEST", TRACE_ID)


def test_post_dto_rule_contract_failure_uses_422(client: TestClient) -> None:
    payload = _valid_request()
    payload["evaluationCutoffAt"] = "2026-07-23T12:00:01Z"

    response = _post(client, payload)

    _assert_error_envelope(response, 422, "RULE_CONTRACT_ERROR", TRACE_ID)
    assert response.json()["fieldErrors"] == []


class _RaisingService:
    def __init__(self, error: Exception) -> None:
        self.error = error
        self.calls = 0

    def analyze(self, request):
        self.calls += 1
        raise self.error


class _DelegatingService:
    def __init__(self, delegate: RuleAnalysisService) -> None:
        self.delegate = delegate
        self.calls = 0

    def analyze(self, request):
        self.calls += 1
        return self.delegate.analyze(request)


def _override_service(application: FastAPI, service: object) -> None:
    application.dependency_overrides[get_rule_analysis_service] = lambda: service


@pytest.mark.parametrize(
    ("invalid_bridge", "internal_detail"),
    [
        (
            MappingProxyType(
                {
                    "RECENT_BENEFICIARY_TRANSFER": RuleId.R004,
                    "SERVER_ONLY_DUPLICATE": RuleId.R004,
                }
            ),
            "SERVER_ONLY_DUPLICATE",
        ),
        (
            MappingProxyType(
                {
                    "TRANSFER_ABSOLUTE_HIGH_AMOUNT": RuleId.R001,
                    "RECENT_DEVICE_REGISTRATION_HIGH_AMOUNT": RuleId.R002,
                    "RECENT_SECURITY_CHANGE_HIGH_AMOUNT": RuleId.R003,
                }
            ),
            "canonical Rule v1 mapping",
        ),
    ],
)
def test_real_builder_server_bridge_corruption_uses_500_internal_error(
    client: TestClient,
    monkeypatch: pytest.MonkeyPatch,
    caplog: pytest.LogCaptureFixture,
    invalid_bridge: Mapping[str, RuleId],
    internal_detail: str,
) -> None:
    monkeypatch.setattr(execution_plan_module, "_RULE_CODE_TO_RULE_ID", invalid_bridge)

    response = _post(client, _valid_request())

    _assert_error_envelope(response, 500, "INTERNAL_ERROR", TRACE_ID)
    assert response.json()["fieldErrors"] == []
    assert "analysis" not in response.json()
    assert internal_detail not in response.text
    assert internal_detail not in caplog.text


def test_duplicate_snapshot_is_rejected_by_mapper_before_builder(
    client: TestClient,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    build_calls = 0
    original_build = RuleExecutionPlanBuilder.build

    def build_spy(*args: object, **kwargs: object) -> object:
        nonlocal build_calls
        build_calls += 1
        return original_build(*args, **kwargs)

    monkeypatch.setattr(RuleExecutionPlanBuilder, "build", build_spy)
    payload = _valid_request()
    rule_versions = payload["ruleVersions"]
    assert isinstance(rule_versions, list)
    duplicate = dict(rule_versions[0])
    duplicate["fraudRuleId"] = "40000000-0000-4000-8000-000000000014"
    duplicate["ruleVersionId"] = "20000000-0000-4000-8000-000000000014"
    rule_versions.append(duplicate)

    response = _post(client, payload)

    _assert_error_envelope(response, 422, "RULE_CONTRACT_ERROR", TRACE_ID)
    assert "analysis" not in response.json()
    # Mapper owns duplicate Snapshot validation; Builder origin is tested separately.
    assert build_calls == 0


def test_real_builder_missing_registry_capability_uses_500_unsupported(
    application: FastAPI,
    client: TestClient,
) -> None:
    registry = RuleEvaluatorRegistry(())
    service = RuleAnalysisService(
        plan_builder=RuleExecutionPlanBuilder(registry),
        plan_runner=RuleExecutionPlanRunner(RuleExecutionOrchestrator(registry)),
    )
    _override_service(application, service)

    response = _post(client, _valid_request())

    _assert_error_envelope(response, 500, "UNSUPPORTED_RULE_CAPABILITY", TRACE_ID)
    assert response.json()["fieldErrors"] == []
    assert "analysis" not in response.json()


def test_request_contract_plan_error_handler_maps_to_422(
    application: FastAPI,
    client: TestClient,
) -> None:
    service = _RaisingService(
        RuleExecutionPlanError(
            RuleExecutionPlanErrorCategory.MISSING_RULE_DEPENDENCY,
            "sensitive internal plan detail",
        )
    )
    _override_service(application, service)

    response = _post(client, _valid_request())

    _assert_error_envelope(response, 422, "RULE_CONTRACT_ERROR", TRACE_ID)
    assert response.json()["fieldErrors"] == [
        {
            "field": "ruleVersions",
            "code": "MISSING_RULE_DEPENDENCY",
            "reason": "RuleVersion 실행 계약을 만족하지 않습니다.",
        }
    ]
    assert "sensitive internal plan detail" not in response.text


@pytest.mark.parametrize(
    "error",
    [
        RuleExecutionPlanError(
            RuleExecutionPlanErrorCategory.UNSUPPORTED_RULE_CAPABILITY,
            "builder registry deployment mismatch",
            origin=RuleExecutionPlanErrorOrigin.DEPLOYED_CAPABILITY,
        ),
        RuleExecutionPlanRunnerError(
            RuleExecutionPlanRunnerErrorCategory.UNSUPPORTED_RULE_CAPABILITY,
            "runner registry deployment mismatch",
        ),
    ],
)
def test_builder_and_runner_capability_mismatch_use_500_unsupported(
    application: FastAPI,
    client: TestClient,
    error: Exception,
) -> None:
    service = _RaisingService(error)
    _override_service(application, service)

    response = _post(client, _valid_request())

    _assert_error_envelope(response, 500, "UNSUPPORTED_RULE_CAPABILITY", TRACE_ID)
    assert response.json()["fieldErrors"] == []
    assert str(error) not in response.text


@pytest.mark.parametrize(
    "error",
    [
        RuleExecutionPlanRunnerError(
            RuleExecutionPlanRunnerErrorCategory.RULE_EVALUATOR_EXECUTION_FAILED,
            "runner sensitive detail",
        ),
        RuleScoringError(
            RuleScoringErrorCategory.INVALID_SCORING_POLICY,
            "scoring sensitive detail",
        ),
        RuleEvidenceError(
            RuleEvidenceErrorCategory.INVALID_RULE_EVIDENCE_FACTS,
            "evidence sensitive detail",
        ),
        RuntimeError("unexpected sensitive detail"),
    ],
)
def test_internal_failures_use_500_without_exception_detail(
    application: FastAPI,
    client: TestClient,
    caplog: pytest.LogCaptureFixture,
    error: Exception,
) -> None:
    service = _RaisingService(error)
    _override_service(application, service)

    response = _post(client, _valid_request())

    _assert_error_envelope(response, 500, "INTERNAL_ERROR", TRACE_ID)
    assert response.json()["fieldErrors"] == []
    assert str(error) not in response.text
    assert str(error) not in caplog.text


def test_exactly_one_mib_of_valid_json_is_accepted_and_one_extra_byte_is_rejected(
    client: TestClient,
) -> None:
    compact_json = json.dumps(_valid_request(), separators=(",", ":")).encode()
    exact_body = compact_json + b" " * (MAX_RULE_ANALYSIS_BODY_BYTES - len(compact_json))
    assert len(exact_body) == MAX_RULE_ANALYSIS_BODY_BYTES
    assert json.loads(exact_body) == _valid_request()

    accepted = client.post(
        ENDPOINT,
        content=exact_body,
        headers={"Content-Type": "application/json", "X-Trace-Id": TRACE_ID},
    )
    rejected = client.post(
        ENDPOINT,
        content=exact_body + b" ",
        headers={"Content-Type": "application/json", "X-Trace-Id": TRACE_ID},
    )

    assert accepted.status_code == 200
    _assert_error_envelope(rejected, 413, "PAYLOAD_TOO_LARGE", TRACE_ID)
    assert rejected.json()["fieldErrors"] == []


def test_invalid_trace_wins_over_payload_size_and_rule_service_is_not_called(
    application: FastAPI,
    client: TestClient,
) -> None:
    service = _RaisingService(AssertionError("Rule execution must not be called"))
    _override_service(application, service)
    oversized = b"{" + b" " * MAX_RULE_ANALYSIS_BODY_BYTES

    response = client.post(
        ENDPOINT,
        content=oversized,
        headers={"Content-Type": "application/json", "X-Trace-Id": "invalid"},
    )

    assert response.status_code == 400
    assert response.json()["code"] == "INVALID_REQUEST"
    assert service.calls == 0


def test_oversized_multichunk_body_is_rejected_before_downstream_app() -> None:
    downstream_calls = 0

    async def downstream(scope, receive, send) -> None:
        nonlocal downstream_calls
        downstream_calls += 1

    middleware = RuleAnalysisHttpMiddleware(downstream)
    scope = _asgi_scope()
    messages = iter(
        [
            {
                "type": "http.request",
                "body": b"x" * MAX_RULE_ANALYSIS_BODY_BYTES,
                "more_body": True,
            },
            {"type": "http.request", "body": b"y", "more_body": False},
        ]
    )
    sent: list[dict[str, object]] = []

    async def receive():
        return next(messages)

    async def send(message):
        sent.append(message)

    asyncio.run(middleware(scope, receive, send))

    assert downstream_calls == 0
    start = next(message for message in sent if message["type"] == "http.response.start")
    body = next(message for message in sent if message["type"] == "http.response.body")
    assert start["status"] == 413
    assert json.loads(body["body"])["code"] == "PAYLOAD_TOO_LARGE"


def test_invalid_trace_does_not_read_any_body_chunk() -> None:
    async def downstream(scope, receive, send) -> None:
        raise AssertionError("downstream app must not be called")

    middleware = RuleAnalysisHttpMiddleware(downstream)
    scope = _asgi_scope(trace_id="invalid")
    sent: list[dict[str, object]] = []

    async def receive():
        raise AssertionError("body must not be read before rejecting Trace")

    async def send(message):
        sent.append(message)

    asyncio.run(middleware(scope, receive, send))

    start = next(message for message in sent if message["type"] == "http.response.start")
    assert start["status"] == 400


def test_raw_header_name_is_case_insensitive() -> None:
    downstream_calls = 0

    async def downstream(scope, receive, send) -> None:
        nonlocal downstream_calls
        downstream_calls += 1
        assert scope["state"]["trace_id"] == TRACE_ID
        await send({"type": "http.response.start", "status": 204, "headers": []})
        await send({"type": "http.response.body", "body": b""})

    middleware = RuleAnalysisHttpMiddleware(downstream)
    scope = _asgi_scope()
    scope["headers"][-1] = (b"X-TrAcE-Id", TRACE_ID.encode())
    sent: list[dict[str, object]] = []

    async def receive():
        return {"type": "http.request", "body": b"", "more_body": False}

    async def send(message):
        sent.append(message)

    asyncio.run(middleware(scope, receive, send))

    assert downstream_calls == 1
    start = next(message for message in sent if message["type"] == "http.response.start")
    assert start["status"] == 204
    assert (b"x-trace-id", TRACE_ID.encode()) in start["headers"]


def test_non_ascii_raw_trace_value_is_rejected_without_decoding_or_exposure() -> None:
    async def downstream(scope, receive, send) -> None:
        raise AssertionError("downstream app must not be called")

    middleware = RuleAnalysisHttpMiddleware(downstream)
    scope = _asgi_scope()
    invalid_value = b"trace_\xff_endpoint_0001"
    scope["headers"][-1] = (b"x-trace-id", invalid_value)
    sent: list[dict[str, object]] = []

    async def receive():
        raise AssertionError("body must not be read before rejecting Trace")

    async def send(message):
        sent.append(message)

    asyncio.run(middleware(scope, receive, send))

    start = next(message for message in sent if message["type"] == "http.response.start")
    body = next(message for message in sent if message["type"] == "http.response.body")
    assert start["status"] == 400
    assert invalid_value not in body["body"]


@pytest.mark.parametrize(
    ("path", "expected_middleware_match"),
    [
        (ENDPOINT, True),
        (V2_ENDPOINT, True),
        ("/api/v2/rule-analysis/extra", False),
        ("/internal/api/v2/rule-analysis", False),
    ],
)
def test_middleware_uses_only_the_exact_v1_v2_path_allowlist(
    path: str,
    expected_middleware_match: bool,
) -> None:
    async def downstream(scope, receive, send) -> None:
        assert ("trace_id" in scope["state"]) is expected_middleware_match
        await send({"type": "http.response.start", "status": 204, "headers": []})
        await send({"type": "http.response.body", "body": b""})

    middleware = RuleAnalysisHttpMiddleware(downstream)
    scope = _asgi_scope(path=path)
    sent: list[dict[str, object]] = []

    async def receive():
        return {"type": "http.request", "body": b"", "more_body": False}

    async def send(message):
        sent.append(message)

    asyncio.run(middleware(scope, receive, send))

    start = next(message for message in sent if message["type"] == "http.response.start")
    trace_headers = [header for header in start["headers"] if header[0].lower() == b"x-trace-id"]
    assert bool(trace_headers) is expected_middleware_match


def test_health_is_outside_rule_analysis_middleware(client: TestClient) -> None:
    response = client.get("/api/health", headers={"X-Trace-Id": "invalid"})

    assert response.status_code == 200
    assert response.json() == {"status": "UP", "service": "ai-service"}
    assert "X-Trace-Id" not in response.headers


def _asgi_scope(
    trace_id: str = TRACE_ID,
    *,
    path: str = ENDPOINT,
) -> dict[str, object]:
    return {
        "type": "http",
        "asgi": {"version": "3.0"},
        "http_version": "1.1",
        "method": "POST",
        "scheme": "http",
        "path": path,
        "raw_path": path.encode(),
        "query_string": b"",
        "root_path": "",
        "headers": [
            (b"content-type", b"application/json"),
            (b"x-trace-id", trace_id.encode()),
        ],
        "client": ("127.0.0.1", 12345),
        "server": ("testserver", 80),
        "state": {},
    }
