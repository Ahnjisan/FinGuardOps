"""Pinned local synthetic SCN-004/005 windows layered on the unchanged v4 Rule set."""

import hashlib
from datetime import timedelta
from decimal import Decimal

from finguardops_ai.mappers.rule_analysis import RuleAnalysisRequestError
from finguardops_ai.schemas.scenario_rule import (
    RuleAnalysisRequestV3,
    RuleAnalysisRequestV4,
    RuleAnalysisResponseV3,
    ScenarioWindowDto,
)
from finguardops_ai.services.scenario_rule import analyze as analyze_v4

_CODES = ("DISTRIBUTED_OUTBOUND_WINDOW", "MANY_CUSTOMER_INBOUND_WINDOW")
_SIGNALS = ("THREE_RECIPIENTS_TEN_MINUTES", "THREE_CUSTOMERS_TWENTY_FOUR_HOURS")
_THRESHOLDS = (Decimal("3000000"), Decimal("5000000"))
_WINDOWS = (timedelta(minutes=10), timedelta(hours=24))


def analyze(request: RuleAnalysisRequestV4, trace_id: str) -> RuleAnalysisResponseV3:
    if request.aggregation_facts.source_version != "SCN004-005-contract-v1":
        raise RuleAnalysisRequestError("Invalid aggregation source version")
    if len(request.rule_versions) != 12:
        raise RuleAnalysisRequestError("Rule v5 requires twelve versions")
    for index, rule in enumerate(request.rule_versions[10:]):
        if (
            rule.rule_code != _CODES[index]
            or rule.reason_code != _CODES[index]
            or rule.version_number != 1
            or rule.weight != 25
            or rule.condition_definition
            != {"contractVersion": "SCN004-005-contract-v1", "signal": _SIGNALS[index]}
            or rule.effective_from > request.evaluation_cutoff_at
            or rule.effective_to is not None
            and request.evaluation_cutoff_at >= rule.effective_to
        ):
            raise RuleAnalysisRequestError("Rule v5 binding mismatch")
    if len({rule.rule_version_id for rule in request.rule_versions}) != 12:
        raise RuleAnalysisRequestError("Duplicate Rule v5 identity")
    payload = request.model_dump(by_alias=True, mode="json")
    payload.pop("aggregationFacts")
    payload["ruleVersions"] = payload["ruleVersions"][:10]
    base = analyze_v4(RuleAnalysisRequestV3.model_validate(payload), trace_id)
    result = base.model_dump(by_alias=True, mode="json")
    score = base.analysis.scoring_result.risk_score
    raw = applied = 0
    transfer = request.transaction.transaction_type in {"ACCOUNT_TRANSFER", "OPEN_BANKING_TRANSFER"}
    windows = (request.aggregation_facts.outbound, request.aggregation_facts.inbound)
    hits = tuple(
        transfer
        and window.coverage_fact_id is not None
        and window.unknown_count == 0
        and window.transfer_count >= 3
        and window.distinct_count >= 3
        and window.total_amount >= _THRESHOLDS[index]
        for index, window in enumerate(windows)
    )
    base_contributions = base.analysis.scoring_result.rule_contributions
    independent = (
        score >= 50
        or hits[0]
        and (base_contributions[3].matched or base_contributions[4].matched)
        or hits[1]
        and base_contributions[4].matched
    )
    ceiling = 100 if independent or not any(hits) else 49
    for index, window in enumerate(windows):
        _validate_window(window, request.evaluation_cutoff_at, transfer)
        hit = hits[index]
        original = 25 if hit else 0
        accepted = min(original, 40 - applied, max(0, ceiling - score))
        raw += original
        applied += accepted
        score += accepted
        result["analysis"]["scoringResult"]["ruleContributions"].append(
            {
                "ruleId": f"R{index + 11:03d}",
                "executionOrder": index + 11,
                "matched": hit,
                "originalContribution": original,
                "appliedContribution": accepted,
            }
        )
        if hit:
            rule = request.rule_versions[index + 10]
            t = request.evaluation_cutoff_at
            result["analysis"]["evidence"].append(
                {
                    "ruleId": f"R{index + 11:03d}",
                    "ruleVersionId": str(rule.rule_version_id),
                    "ruleCode": rule.rule_code,
                    "ruleVersion": "1",
                    "reasonCode": rule.reason_code,
                    "executionOrder": index + 11,
                    "scoreContribution": original,
                    "appliedContribution": accepted,
                    "observationSummary": {
                        "sourceVersion": "SCN004-005-contract-v1",
                        "mockSourceCode": "LOCAL_FDS_MOCK",
                        "mockSourceVersion": "v1",
                        "windowStartAt": _wire(t - _WINDOWS[index]),
                        "evaluationCutoffAt": _wire(t),
                        "transferCount": window.transfer_count,
                        "distinctCount": window.distinct_count,
                        "totalAmount": str(int(window.total_amount)),
                        "unknownCount": window.unknown_count,
                        "coverageFactId": str(window.coverage_fact_id),
                    },
                    "evidenceOccurredAt": _wire(t),
                }
            )
    result["analysis"]["ruleSetVersion"] = _rule_set_version(request)
    result["analysis"]["scoringResult"].update(
        scoringPolicyVersion="scoring-policy-v5",
        riskScore=score,
        riskLevel="LOW"
        if score < 20
        else "MEDIUM"
        if score < 50
        else "HIGH"
        if score < 80
        else "CRITICAL",
    )
    result["analysis"]["scoringResult"]["groupSummaries"].append(
        {
            "groupId": "flow_aggregation",
            "rawScore": raw,
            "cap": 40,
            "appliedScore": applied,
            "reduction": raw - applied,
        }
    )
    result["analysis"]["scoringResult"]["ruleContributions"] = tuple(
        result["analysis"]["scoringResult"]["ruleContributions"]
    )
    result["analysis"]["scoringResult"]["groupSummaries"] = tuple(
        result["analysis"]["scoringResult"]["groupSummaries"]
    )
    result["analysis"]["evidence"] = tuple(result["analysis"]["evidence"])
    return RuleAnalysisResponseV3.model_validate(result)


def _validate_window(window: ScenarioWindowDto, cutoff, transfer: bool) -> None:
    if (
        window.transfer_count < 0
        or window.distinct_count < 0
        or window.distinct_count > window.transfer_count
        or window.unknown_count < 0
        or window.total_amount < 0
        or window.total_amount != window.total_amount.to_integral_value()
        or not transfer
        and (
            window.transfer_count != 0
            or window.distinct_count != 0
            or window.total_amount != 0
            or window.coverage_fact_id is not None
        )
    ):
        raise RuleAnalysisRequestError("Invalid cutoff-fixed aggregation window")


def _wire(value) -> str:
    return value.isoformat().replace("+00:00", "Z")


def _rule_set_version(request: RuleAnalysisRequestV4) -> str:
    lines = ["rule-plan-v5"]
    for index, rule in enumerate(request.rule_versions):
        lines.append(
            f"{index + 1}\t{rule.rule_version_id}\t{rule.rule_code}"
            f"\tR{index + 1:03d}\t{rule.version_number}"
        )
    return hashlib.sha256(("\n".join(lines) + "\n").encode()).hexdigest()
