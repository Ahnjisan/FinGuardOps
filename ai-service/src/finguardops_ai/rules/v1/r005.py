"""SCN-003 exact external recipient match, local synthetic policy only."""

from datetime import timedelta

from finguardops_ai.rules.v1.models import (
    R005Facts,
    RuleEvaluationInput,
    RuleEvaluationResult,
    RuleId,
)


def evaluate_r005(rule_input: RuleEvaluationInput) -> RuleEvaluationResult[R005Facts]:
    transaction = rule_input.transaction
    snapshot = rule_input.external_risk
    if snapshot is None:
        raise ValueError("R005 requires a validated External Risk snapshot")
    if transaction.recipient_account_ref is None:
        return RuleEvaluationResult(rule_id=RuleId.R005, matched=False, facts=None)
    cutoff = transaction.occurred_at
    if not (
        cutoff - timedelta(hours=24) <= snapshot.provider_as_of <= cutoff <= snapshot.looked_up_at
    ):
        raise ValueError("R005 External Risk snapshot is outside the approved cutoff")
    matched = any(
        match.subject_type == "RECIPIENT_ACCOUNT"
        and match.external_risk_type == "SUSPICIOUS_ACCOUNT"
        and match.reason_code == "SUSPICIOUS_RECIPIENT_ACCOUNT"
        for match in snapshot.matches
    )
    return RuleEvaluationResult(
        rule_id=RuleId.R005,
        matched=matched,
        facts=R005Facts(
            provider_code=snapshot.provider_code,
            provider_as_of=snapshot.provider_as_of,
            looked_up_at=snapshot.looked_up_at,
            freshness_seconds=int((cutoff - snapshot.provider_as_of).total_seconds()),
        )
        if matched
        else None,
    )
