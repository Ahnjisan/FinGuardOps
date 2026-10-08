from dataclasses import replace

from tests.rules.v1.test_scoring import _plan

from finguardops_ai.rules.v1 import (
    PlannedRuleResult,
    RiskLevel,
    RuleEvaluationResult,
    RuleId,
    RuleScoringCalculator,
    ScoringGroupId,
)


def test_v2_four_distinct_rules_are_critical_while_v1_stays_high() -> None:
    original = _plan()
    v2 = replace(original, items=tuple(replace(item, version_number=2)
                                       for item in original.items))
    def score(plan):
        return RuleScoringCalculator.calculate(plan, tuple(
            PlannedRuleResult(item, RuleEvaluationResult(item.rule_id, True, object()))
            for item in plan.items))

    assert score(original).risk_score == 75
    result = score(v2)
    assert result.risk_score == 85
    assert result.risk_level is RiskLevel.CRITICAL
    assert result.scoring_policy_version == "scoring-policy-v2"
    assert [summary.group_id for summary in result.group_summaries] == [
        ScoringGroupId.AMOUNT, ScoringGroupId.SECURITY, ScoringGroupId.BENEFICIARY]
    assert [item.original_contribution for item in result.rule_contributions] == [15, 20, 40, 10]


def test_v2_needs_all_four_independent_matches_for_critical() -> None:
    original = _plan()
    plan = replace(original, items=tuple(replace(item, version_number=2)
                                         for item in original.items))
    for absent in (RuleId.R001, RuleId.R002, RuleId.R003, RuleId.R004):
        result = RuleScoringCalculator.calculate(plan, tuple(
            PlannedRuleResult(item, RuleEvaluationResult(
                item.rule_id, item.rule_id is not absent,
                None if item.rule_id is absent else object()))
            for item in plan.items))
        assert result.risk_score < 80
