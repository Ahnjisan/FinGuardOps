from test_ai_report import sample

from finguardops_ai.services.ai_report_fallback import build_fallback


def test_fallback_uses_only_adopted_rule_code_and_no_timeline() -> None:
    report = build_fallback(sample())
    assert report.keyReasons[0].reasonCode == "NEW_DEVICE"
    assert "타임라인" not in report.summary


def test_fallback_deduplicates_shared_reason_codes() -> None:
    request = sample()
    duplicated = request.model_copy(update={"ruleEvidence": request.ruleEvidence * 2})
    assert len(build_fallback(duplicated).keyReasons) == 1
