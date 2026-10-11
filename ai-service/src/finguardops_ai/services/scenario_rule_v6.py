"""Pinned local synthetic SCN-006/007 source-to-use Rule set."""

import hashlib
from decimal import Decimal

from finguardops_ai.mappers.rule_analysis import RuleAnalysisRequestError
from finguardops_ai.schemas.scenario_rule import (
    RuleAnalysisRequestV4,
    RuleAnalysisRequestV5,
    RuleAnalysisResponseV3,
    ScenarioFundFlowDto,
)
from finguardops_ai.services.scenario_rule_v5 import analyze as analyze_v5

_CODES = ("VERIFIED_INBOUND_ATM_DRAIN", "VERIFIED_LOAN_NEW_RECIPIENT_DRAIN")
_SIGNALS = (
    "APPROVED_INBOUND_LINKED_ATM_EIGHTY_PERCENT",
    "APPROVED_LOAN_LINKED_NEW_RECIPIENT_EIGHTY_PERCENT",
)


def analyze(request: RuleAnalysisRequestV5, trace_id: str) -> RuleAnalysisResponseV3:
    if request.fund_flow_facts.source_version != "SCN006-007-contract-v1":
        raise RuleAnalysisRequestError("Invalid fund flow source version")
    if len(request.rule_versions) != 14:
        raise RuleAnalysisRequestError("Rule v6 requires fourteen versions")
    for index, rule in enumerate(request.rule_versions[12:]):
        if (
            rule.rule_code != _CODES[index]
            or rule.reason_code != _CODES[index]
            or rule.version_number != 1
            or rule.weight != 50
            or rule.condition_definition
            != {"contractVersion": "SCN006-007-contract-v1", "signal": _SIGNALS[index]}
            or rule.effective_from > request.evaluation_cutoff_at
            or rule.effective_to is not None
            and request.evaluation_cutoff_at >= rule.effective_to
        ):
            raise RuleAnalysisRequestError("Rule v6 binding mismatch")
    if len({rule.rule_version_id for rule in request.rule_versions}) != 14:
        raise RuleAnalysisRequestError("Duplicate Rule v6 identity")
    payload = request.model_dump(by_alias=True, mode="json")
    payload.pop("fundFlowFacts")
    payload["ruleVersions"] = payload["ruleVersions"][:12]
    base = analyze_v5(RuleAnalysisRequestV4.model_validate(payload), trace_id)
    result = base.model_dump(by_alias=True, mode="json")
    score = base.analysis.scoring_result.risk_score
    base_rows = base.analysis.scoring_result.rule_contributions
    flows = (request.fund_flow_facts.atm, request.fund_flow_facts.loan)
    for flow in flows:
        _validate_flow(flow, request.evaluation_cutoff_at)
    if flows[0].status == "AVAILABLE" and flows[0].beneficiary_event_id is not None:
        raise RuleAnalysisRequestError("ATM flow has beneficiary evidence")
    if flows[1].status == "AVAILABLE" and flows[1].beneficiary_event_id is None:
        raise RuleAnalysisRequestError("Loan flow lacks new beneficiary evidence")
    hits = tuple(flow.status == "AVAILABLE" and flow.ratio_basis_points >= 8000 for flow in flows)
    if hits[0] and request.transaction.transaction_type != "ATM_WITHDRAWAL":
        raise RuleAnalysisRequestError("ATM flow does not match transaction type")
    if hits[1] and request.transaction.transaction_type not in {
        "ACCOUNT_TRANSFER",
        "OPEN_BANKING_TRANSFER",
    }:
        raise RuleAnalysisRequestError("Loan flow does not match transaction type")
    independent = any(base_rows[i].matched for i in (1, 2, 4, 6, 7, 8, 9))
    ceiling = 100 if independent or not any(hits) else max(score, 79)
    raw = applied = 0
    for index, flow in enumerate(flows):
        hit = hits[index]
        original = 50 if hit else 0
        accepted = min(original, 50 - applied, max(0, ceiling - score))
        raw += original
        applied += accepted
        score += accepted
        result["analysis"]["scoringResult"]["ruleContributions"].append(
            {
                "ruleId": f"R{index + 13:03d}",
                "executionOrder": index + 13,
                "matched": hit,
                "originalContribution": original,
                "appliedContribution": accepted,
            }
        )
        if hit:
            rule = request.rule_versions[index + 12]
            observation = {
                "sourceVersion": "SCN006-007-contract-v1",
                "mockSourceCode": "LOCAL_FDS_MOCK",
                "mockSourceVersion": "v2",
                "evaluationCutoffAt": _wire(request.evaluation_cutoff_at),
                "sourceTransactionId": str(flow.source_transaction_id),
                "currentLinkId": str(flow.current_link_id),
                "historyCoverageFactId": str(flow.history_coverage_fact_id),
                "sourceOccurredAt": _wire(flow.source_occurred_at),
                "sourceApprovedAt": _wire(flow.source_approved_at),
                "sourceAmount": str(int(flow.source_amount)),
                "linkedAmount": str(int(flow.linked_amount)),
                "ratioBasisPoints": flow.ratio_basis_points,
                "linkedPriorTransactionIds": [
                    str(identifier) for identifier in flow.linked_prior_transaction_ids
                ],
            }
            if flow.beneficiary_event_id is not None:
                observation["beneficiaryEventId"] = str(flow.beneficiary_event_id)
            result["analysis"]["evidence"].append(
                {
                    "ruleId": f"R{index + 13:03d}",
                    "ruleVersionId": str(rule.rule_version_id),
                    "ruleCode": rule.rule_code,
                    "ruleVersion": "1",
                    "reasonCode": rule.reason_code,
                    "executionOrder": index + 13,
                    "scoreContribution": original,
                    "appliedContribution": accepted,
                    "observationSummary": observation,
                    "evidenceOccurredAt": _wire(request.evaluation_cutoff_at),
                }
            )
    result["analysis"]["ruleSetVersion"] = _rule_set_version(request)
    result["analysis"]["scoringResult"].update(
        scoringPolicyVersion="scoring-policy-v6",
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
            "groupId": "flow_link",
            "rawScore": raw,
            "cap": 50,
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


def _validate_flow(flow: ScenarioFundFlowDto, cutoff) -> None:
    if (
        flow.status not in {"UNKNOWN", "AVAILABLE"}
        or flow.source_amount < 0
        or flow.linked_amount < 0
    ):
        raise RuleAnalysisRequestError("Invalid fund flow status or amount")
    if flow.status == "UNKNOWN":
        if (
            any(
                value is not None
                for value in (
                    flow.source_transaction_id,
                    flow.current_link_id,
                    flow.history_coverage_fact_id,
                    flow.beneficiary_event_id,
                    flow.source_occurred_at,
                    flow.source_approved_at,
                )
            )
            or flow.linked_prior_transaction_ids
            or flow.source_amount != 0
            or flow.linked_amount != 0
            or flow.ratio_basis_points != 0
        ):
            raise RuleAnalysisRequestError("Unknown fund flow contains attributed funds")
        return
    if (
        flow.source_transaction_id is None
        or flow.current_link_id is None
        or flow.history_coverage_fact_id is None
        or flow.source_occurred_at is None
        or flow.source_approved_at is None
        or flow.source_occurred_at >= cutoff
        or flow.source_approved_at > cutoff
        or flow.source_amount <= 0
        or flow.linked_amount > flow.source_amount
        or flow.source_amount != flow.source_amount.to_integral_value()
        or flow.linked_amount != flow.linked_amount.to_integral_value()
        or flow.ratio_basis_points != int(flow.linked_amount * Decimal(10000) // flow.source_amount)
    ):
        raise RuleAnalysisRequestError("Unproved source-to-use flow")


def _wire(value) -> str:
    return value.isoformat().replace("+00:00", "Z")


def _rule_set_version(request: RuleAnalysisRequestV5) -> str:
    lines = ["rule-plan-v6"]
    for index, rule in enumerate(request.rule_versions):
        lines.append(
            f"{index + 1}\t{rule.rule_version_id}\t{rule.rule_code}"
            f"\tR{index + 1:03d}\t{rule.version_number}"
        )
    return hashlib.sha256(("\n".join(lines) + "\n").encode()).hexdigest()
