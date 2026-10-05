"""Deterministic draft from validated RULE evidence, without invented events."""

from finguardops_ai.schemas.ai_report import KeyReason, ReportContent, ReportRequest

SAFE_SUMMARY_TEMPLATE = "채택 탐지 결과는 {risk_level} 등급입니다. 이 문서는 조사 보조 초안입니다."
SAFE_REASON_TEMPLATE = "채택된 RULE {rule_code}의 탐지 근거를 확인하세요."
SAFE_CHECKLIST = (
    "채택된 RULE 근거와 원거래를 대조하세요.",
    "사유 코드와 RULE 버전을 확인하세요.",
    "채택 탐지 결과의 위험 등급과 점수 근거를 검토하세요.",
)


def build_fallback(request: ReportRequest) -> ReportContent:
    if not request.ruleEvidence:
        raise ValueError("RULE evidence is required")
    reasons = []
    seen: set[str] = set()
    for item in request.ruleEvidence:
        if item.reasonCode in seen:
            continue
        seen.add(item.reasonCode)
        reasons.append(
            KeyReason(
                reasonCode=item.reasonCode,
                description=SAFE_REASON_TEMPLATE.format(rule_code=item.ruleCode),
            )
        )
    return ReportContent(
        summary=SAFE_SUMMARY_TEMPLATE.format(risk_level=request.riskLevel),
        keyReasons=reasons,
        investigationChecklist=[SAFE_CHECKLIST[0]],
    )
