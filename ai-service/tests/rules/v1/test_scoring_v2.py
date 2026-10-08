from dataclasses import replace
from datetime import UTC, datetime, timedelta
from uuid import UUID

from finguardops_ai.rules.v1 import (
    BehaviorEventType,
    PlannedRuleResult,
    R004ConditionDefinition,
    RiskLevel,
    RuleEvaluationResult,
    RuleExecutionPlan,
    RuleExecutionPlanItem,
    RuleId,
    RuleScoringCalculator,
    ScoringGroupId,
)


def _plan() -> RuleExecutionPlan:
    cutoff = datetime(2026, 7, 23, 12, 0, tzinfo=UTC)
    codes = (
        "TRANSFER_ABSOLUTE_HIGH_AMOUNT",
        "RECENT_DEVICE_REGISTRATION_HIGH_AMOUNT",
        "RECENT_SECURITY_CHANGE_HIGH_AMOUNT",
        "RECENT_BENEFICIARY_TRANSFER",
    )
    condition = R004ConditionDefinition(
        event_type=BehaviorEventType.BENEFICIARY_REGISTERED,
        window_seconds=86400,
        match_policy="SAME_CUSTOMER_SENDER_ACCOUNT_AND_BENEFICIARY",
        selection_policy="LATEST_OCCURRED_AT_THEN_EVENT_ID_ASC",
    )
    return RuleExecutionPlan(
        evaluation_cutoff_at=cutoff,
        rule_set_version="rule-set-version",
        items=tuple(
            RuleExecutionPlanItem(
                rule_version_id=UUID(f"20000000-0000-4000-8000-00000000000{order}"),
                rule_code=code,
                rule_id=rule_id,
                version_number=1,
                reason_code=code,
                weight=weight,
                condition_definition=condition,
                effective_from=cutoff - timedelta(days=1),
                effective_to=cutoff + timedelta(days=1),
                execution_order=order,
            )
            for order, (rule_id, code, weight) in enumerate(
                zip(
                    (RuleId.R001, RuleId.R002, RuleId.R003, RuleId.R004),
                    codes,
                    (15, 20, 40, 10),
                    strict=True,
                ),
                start=1,
            )
        ),
    )


def test_v2_four_distinct_rules_are_critical_while_v1_stays_high() -> None:
    original = _plan()
    v2 = replace(original, items=tuple(replace(item, version_number=2) for item in original.items))

    def score(plan):
        return RuleScoringCalculator.calculate(
            plan,
            tuple(
                PlannedRuleResult(item, RuleEvaluationResult(item.rule_id, True, object()))
                for item in plan.items
            ),
        )

    assert score(original).risk_score == 75
    result = score(v2)
    assert result.risk_score == 85
    assert result.risk_level is RiskLevel.CRITICAL
    assert result.scoring_policy_version == "scoring-policy-v2"
    assert [summary.group_id for summary in result.group_summaries] == [
        ScoringGroupId.AMOUNT,
        ScoringGroupId.SECURITY,
        ScoringGroupId.BENEFICIARY,
    ]
    assert [item.original_contribution for item in result.rule_contributions] == [15, 20, 40, 10]


def test_v2_needs_all_four_independent_matches_for_critical() -> None:
    original = _plan()
    plan = replace(
        original, items=tuple(replace(item, version_number=2) for item in original.items)
    )
    for absent in (RuleId.R001, RuleId.R002, RuleId.R003, RuleId.R004):
        result = RuleScoringCalculator.calculate(
            plan,
            tuple(
                PlannedRuleResult(
                    item,
                    RuleEvaluationResult(
                        item.rule_id,
                        item.rule_id is not absent,
                        None if item.rule_id is absent else object(),
                    ),
                )
                for item in plan.items
            ),
        )
        assert result.risk_score < 80
