import json

import pytest
from fastapi.testclient import TestClient

import finguardops_ai.services.ai_report as report_module
from finguardops_ai.core.config import Settings
from finguardops_ai.main import create_app
from finguardops_ai.schemas.ai_report import ReportRequest, RuleEvidence
from finguardops_ai.services.ai_report import AiReportService, public_model_version
from finguardops_ai.services.ai_report_fallback import SAFE_CHECKLIST, build_fallback
from finguardops_ai.services.ollama_client import OllamaFailure


def sample() -> ReportRequest:
    return ReportRequest(
        caseId="11111111-1111-4111-8111-111111111111",
        detectionResultVersion=3,
        riskLevel="HIGH",
        riskScore=81,
        ruleSetVersion="rules-1",
        ruleEvidence=[
            RuleEvidence(
                ruleCode="R001", ruleVersion="1", reasonCode="NEW_DEVICE", scoreContribution=15
            )
        ],
        traceId="trace-test-001",
    )


class FakeOllama:
    def __init__(self, result: str | Exception) -> None:
        self.result = result
        self.calls = 0

    def generate(self, prompt: str) -> tuple[str, int, int, int]:
        self.calls += 1
        assert "externalCustomerRef" not in prompt
        if isinstance(self.result, Exception):
            raise self.result
        return self.result, 21, 31, 8


def test_grounded_report_and_local_usage() -> None:
    grounded = build_fallback(sample()).model_dump()
    grounded["investigationChecklist"] = [SAFE_CHECKLIST[1]]
    body = json.dumps(grounded)
    result = AiReportService(Settings(), FakeOllama(body)).generate(sample())
    assert result.status == "COMPLETED"
    assert result.source == "LLM"
    assert result.attempts[0].inputTokens == 21
    assert result.attempts[0].outputTokens == 31


def test_unverified_model_claim_falls_back_even_with_matching_reason_codes() -> None:
    claimed = build_fallback(sample()).model_dump()
    claimed["summary"] = "고객이 새 기기를 사용한 사실이 확인되었습니다."
    result = AiReportService(Settings(), FakeOllama(json.dumps(claimed))).generate(sample())
    assert result.status == "FALLBACK_COMPLETED"
    assert result.source == "TEMPLATE_FALLBACK"
    assert result.failureCode is None
    assert result.fallbackTriggerCode == "LLM_OUTPUT_REJECTED"


def test_unavailable_timeline_suggestion_is_rejected() -> None:
    claimed = build_fallback(sample()).model_dump()
    claimed["investigationChecklist"] = ["공개되지 않은 행동 타임라인을 확인하세요."]
    result = AiReportService(Settings(), FakeOllama(json.dumps(claimed))).generate(sample())
    assert result.status == "FALLBACK_COMPLETED"
    assert result.failureCode is None
    assert result.fallbackTriggerCode == "LLM_OUTPUT_REJECTED"


def test_unapproved_reason_falls_back_without_exposing_raw_response() -> None:
    body = json.dumps(
        {
            "summary": "허위 사실",
            "keyReasons": [{"reasonCode": "UNSEEN", "description": "허위"}],
            "investigationChecklist": ["확인"],
        }
    )
    result = AiReportService(Settings(), FakeOllama(body)).generate(sample())
    assert result.status == "FALLBACK_COMPLETED"
    assert result.failureCode is None
    assert result.fallbackTriggerCode == "LLM_OUTPUT_REJECTED"
    assert result.content.keyReasons[0].reasonCode == "NEW_DEVICE"
    assert "허위" not in result.model_dump_json()


def test_timeout_falls_back_and_unknown_tokens_remain_unknown() -> None:
    result = AiReportService(Settings(), FakeOllama(OllamaFailure("TIMEOUT"))).generate(sample())
    assert result.status == "FALLBACK_COMPLETED"
    assert len(result.attempts) == 2
    assert result.failureCode is None
    assert result.fallbackTriggerCode == "LLM_TIMEOUT"
    assert result.attempts[0].inputTokens is None
    assert result.attempts[0].outputTokens is None


@pytest.mark.parametrize("code,expected,attempted,calls", [
    ("CONNECTION_FAILED", "LLM_UNAVAILABLE", True, 2),
    ("PROVIDER_ERROR", "LLM_UNAVAILABLE", True, 1),
    ("MODEL_METADATA_UNAVAILABLE", "LLM_UNAVAILABLE", False, 1),
    ("MODEL_NOT_PINNED", "LLM_UNAVAILABLE", False, 1),
])
def test_only_confirmed_connection_failure_retries(
    code: str, expected: str, attempted: bool, calls: int,
) -> None:
    provider = FakeOllama(OllamaFailure(code, attempted=attempted))
    result = AiReportService(Settings(), provider).generate(sample())
    assert result.status == "FALLBACK_COMPLETED"
    assert result.failureCode is None
    assert result.fallbackTriggerCode == expected
    assert provider.calls == calls
    assert len(result.attempts) == (calls if attempted else 0)


def test_invalid_provider_format_and_failed_fallback_end_failed(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(
        "finguardops_ai.services.ai_report.build_fallback",
        lambda request: (_ for _ in ()).throw(ValueError("synthetic fallback failure")),
    )
    result = AiReportService(Settings(), FakeOllama("not-json")).generate(sample())
    assert result.status == "FAILED"
    assert result.content is None
    assert result.failureCode == "TEMPLATE_FALLBACK_FAILED"
    assert result.fallbackTriggerCode == "LLM_OUTPUT_REJECTED"
    assert len(result.attempts) == 1
    assert result.attempts[0].outcome == "INVALID_OUTPUT"


def test_cache_version_changes_with_digest_quantization_and_prompt(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    base = Settings(ollama_model_digest="a" * 64, ollama_quantization="Q4_K_M")
    old = public_model_version(base)
    assert old != public_model_version(
        Settings(ollama_model_digest="b" * 64, ollama_quantization="Q4_K_M")
    )
    assert old != public_model_version(base, ("b" * 64, "Q4_K_M"))
    assert old != public_model_version(base, ("a" * 64, "Q8_0"))
    assert public_model_version(base, ("b" * 64, "Q4_K_M")) != public_model_version(
        Settings(ollama_model_digest="b" * 64, ollama_quantization="Q4_K_M"),
        ("b" * 64, "Q4_K_M"),
    )
    assert old != public_model_version(
        Settings(ollama_model_digest="a" * 64, ollama_quantization="Q8_0")
    )
    assert old != public_model_version(
        Settings(
            ollama_model_digest="a" * 64,
            ollama_quantization="Q4_K_M",
            ai_report_prompt_version="v2",
        )
    )
    monkeypatch.setattr(
        report_module,
        "OLLAMA_CHAT_PARAMETERS",
        {
            "format": "json",
            "think": True,
            "temperature": 0,
        },
    )
    assert old != public_model_version(base)
    monkeypatch.undo()
    monkeypatch.setattr(report_module, "SAFE_CHECKLIST", ("Different review action",))
    assert old != public_model_version(base)
    monkeypatch.undo()
    monkeypatch.setattr(report_module, "SAFE_SUMMARY_TEMPLATE", "Different safe summary")
    assert old != public_model_version(base)


def test_extra_sensitive_field_refused_before_provider_call() -> None:
    app = create_app()
    with TestClient(app) as client:
        result = client.post(
            "/api/v1/ai-reports",
            json={
                "caseId": "11111111-1111-4111-8111-111111111111",
                "detectionResultVersion": 1,
                "riskLevel": "HIGH",
                "riskScore": 80,
                "ruleSetVersion": "rules-1",
                "ruleEvidence": [
                    {
                        "ruleCode": "R001",
                        "ruleVersion": "1",
                        "reasonCode": "NEW_DEVICE",
                        "scoreContribution": 15,
                    }
                ],
                "traceId": "trace-test-001",
                "externalCustomerRef": "sensitive",
            },
        )
    assert result.status_code == 400


def test_model_metadata_is_opaque() -> None:
    app = create_app()
    with TestClient(app) as client:
        response = client.get("/api/v1/ai-reports/model")
    assert response.status_code == 200
    assert response.json()["modelVersion"].startswith("local-")
    assert "qwen" not in response.text.lower()
