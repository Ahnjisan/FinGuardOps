"""Pinned local synthetic SCN-008 open banking concentration Rule set."""

import hashlib
from datetime import timedelta
from decimal import Decimal

from finguardops_ai.mappers.rule_analysis import RuleAnalysisRequestError
from finguardops_ai.schemas.scenario_rule import (
    RuleAnalysisRequestV5,
    RuleAnalysisRequestV6,
    RuleAnalysisResponseV3,
    ScenarioOpenBankingFactsDto,
)
from finguardops_ai.services.scenario_rule_v6 import _wire
from finguardops_ai.services.scenario_rule_v6 import analyze as analyze_v6

_CODE = "VERIFIED_OPEN_BANKING_CONCENTRATION_RESEND"
_SIGNAL = "TWO_OWNED_EXTERNAL_ACCOUNTS_CONCENTRATE_AND_RESEND"


def analyze(request: RuleAnalysisRequestV6, trace_id: str) -> RuleAnalysisResponseV3:
    if len(request.rule_versions) != 15:
        raise RuleAnalysisRequestError("Rule v7 requires fifteen versions")
    rule = request.rule_versions[14]
    if (
        rule.rule_code != _CODE
        or rule.reason_code != _CODE
        or rule.version_number != 1
        or rule.weight != 50
        or rule.condition_definition != {"contractVersion": "SCN008-contract-v1", "signal": _SIGNAL}
        or rule.effective_from > request.evaluation_cutoff_at
        or rule.effective_to is not None
        and request.evaluation_cutoff_at >= rule.effective_to
        or len({item.fraud_rule_id for item in request.rule_versions}) != 15
        or len({item.rule_version_id for item in request.rule_versions}) != 15
    ):
        raise RuleAnalysisRequestError("Rule v7 binding mismatch")
    payload = request.model_dump(by_alias=True, mode="json")
    payload.pop("openBankingFacts")
    payload["ruleVersions"] = payload["ruleVersions"][:14]
    base = analyze_v6(RuleAnalysisRequestV5.model_validate(payload), trace_id)
    facts = request.open_banking_facts
    _validate_facts(facts, request.evaluation_cutoff_at, request.transaction.amount)
    if facts.hit() and request.transaction.transaction_type not in {
        "ACCOUNT_TRANSFER",
        "OPEN_BANKING_TRANSFER",
    }:
        raise RuleAnalysisRequestError("Open banking chain requires a resend transfer")
    result = base.model_dump(by_alias=True, mode="json")
    score = base.analysis.scoring_result.risk_score
    base_rows = base.analysis.scoring_result.rule_contributions
    independent = any(base_rows[i].matched for i in (1, 2, 4, 6, 7, 8, 9))
    raw = 50 if facts.hit() else 0
    ceiling = 100 if independent or not facts.hit() else max(score, 79)
    applied = min(raw, max(0, ceiling - score))
    score += applied
    result["analysis"]["scoringResult"]["ruleContributions"].append(
        {
            "ruleId": "R015",
            "executionOrder": 15,
            "matched": facts.hit(),
            "originalContribution": raw,
            "appliedContribution": applied,
        }
    )
    result["analysis"]["scoringResult"]["groupSummaries"].append(
        {
            "groupId": "open_banking_chain",
            "rawScore": raw,
            "cap": 50,
            "appliedScore": applied,
            "reduction": raw - applied,
        }
    )
    if facts.hit():
        result["analysis"]["evidence"].append(
            {
                "ruleId": "R015",
                "ruleVersionId": str(rule.rule_version_id),
                "ruleCode": _CODE,
                "ruleVersion": "1",
                "reasonCode": _CODE,
                "executionOrder": 15,
                "scoreContribution": raw,
                "appliedContribution": applied,
                "observationSummary": observation(request),
                "evidenceOccurredAt": _wire(request.evaluation_cutoff_at),
            }
        )
    result["analysis"]["ruleSetVersion"] = _rule_set_version(request)
    result["analysis"]["scoringResult"].update(
        scoringPolicyVersion="scoring-policy-v7",
        riskScore=score,
        riskLevel="LOW"
        if score < 20
        else "MEDIUM"
        if score < 50
        else "HIGH"
        if score < 80
        else "CRITICAL",
    )
    result["analysis"]["scoringResult"]["ruleContributions"] = tuple(
        result["analysis"]["scoringResult"]["ruleContributions"]
    )
    result["analysis"]["scoringResult"]["groupSummaries"] = tuple(
        result["analysis"]["scoringResult"]["groupSummaries"]
    )
    result["analysis"]["evidence"] = tuple(result["analysis"]["evidence"])
    return RuleAnalysisResponseV3.model_validate(result)


def observation(request: RuleAnalysisRequestV6) -> dict:
    f = request.open_banking_facts
    return {
        "sourceVersion": f.source_version,
        "mockSourceCode": "LOCAL_OPEN_BANKING_MOCK",
        "mockSourceVersion": "v1",
        "evaluationCutoffAt": _wire(request.evaluation_cutoff_at),
        "historyCoverageFactId": str(f.history_coverage_fact_id),
        "concentrationStartedAt": _wire(f.concentration_started_at),
        "concentrationCompletedAt": _wire(f.concentration_completed_at),
        "concentrationAmount": str(int(f.concentration_amount)),
        "resendAmount": str(int(f.resend_amount)),
        "ratioBasisPoints": f.ratio_basis_points,
        "stages": [
            {
                "sourceTransactionId": str(stage.source_transaction_id),
                "ownershipAssertionId": str(stage.ownership_assertion_id),
                "linkId": str(stage.link_id),
                "occurredAt": _wire(stage.occurred_at),
                "approvedAt": _wire(stage.approved_at),
                "amount": str(int(stage.amount)),
            }
            for stage in f.stages
        ],
    }


def _validate_facts(f: ScenarioOpenBankingFactsDto, cutoff, target_amount: str) -> None:
    if f.source_version != "SCN008-contract-v1" or not 0 <= f.ratio_basis_points <= 10000:
        raise RuleAnalysisRequestError("Invalid open banking facts")
    if f.status == "UNKNOWN":
        if (
            f.history_coverage_fact_id is not None
            or f.concentration_started_at is not None
            or f.concentration_completed_at is not None
            or f.stages
            or f.concentration_amount != 0
            or f.resend_amount != 0
            or f.ratio_basis_points != 0
        ):
            raise RuleAnalysisRequestError("Unknown chain attributes funds")
        return
    if f.status != "AVAILABLE" or f.history_coverage_fact_id is None or len(f.stages) < 2:
        raise RuleAnalysisRequestError("Unproved open banking chain")
    if (
        f.concentration_started_at is None
        or f.concentration_completed_at is None
        or f.concentration_started_at != min(stage.occurred_at for stage in f.stages)
        or f.concentration_completed_at != max(stage.occurred_at for stage in f.stages)
        or f.concentration_started_at < f.concentration_completed_at - timedelta(minutes=30)
        or f.concentration_completed_at >= cutoff
        or f.concentration_completed_at < cutoff - timedelta(minutes=15)
        or len({stage.source_transaction_id for stage in f.stages}) != len(f.stages)
        or len({stage.ownership_assertion_id for stage in f.stages}) < 2
        or any(
            stage.approved_at > cutoff or stage.occurred_at >= cutoff or stage.amount <= 0
            for stage in f.stages
        )
        or f.concentration_amount != sum((stage.amount for stage in f.stages), Decimal(0))
        or f.resend_amount != Decimal(target_amount)
        or f.resend_amount > f.concentration_amount
        or f.ratio_basis_points != int(f.resend_amount * Decimal(10000) // f.concentration_amount)
    ):
        raise RuleAnalysisRequestError("Invalid open banking stage/cutoff ratio")


def _rule_set_version(request: RuleAnalysisRequestV6) -> str:
    lines = ["rule-plan-v7"]
    for index, rule in enumerate(request.rule_versions):
        lines.append(
            f"{index + 1}\t{rule.rule_version_id}\t{rule.rule_code}"
            f"\tR{index + 1:03d}\t{rule.version_number}"
        )
    return hashlib.sha256(("\n".join(lines) + "\n").encode()).hexdigest()
