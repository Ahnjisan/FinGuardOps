"""Explicit Rule v2 entrypoint; wire v2 is a separate HTTP concern."""

from finguardops_ai.rules.v1.execution_plan import RuleExecutionPlan
from finguardops_ai.rules.v1.runner import PlannedRuleResult
from finguardops_ai.rules.v1.scoring import (
    RuleScoringCalculator,
    RuleScoringResult,
)


class RuleV2ScoringCalculator:
    @staticmethod
    def calculate(plan: RuleExecutionPlan,
                  results: tuple[PlannedRuleResult, ...]) -> RuleScoringResult:
        if len(plan.items) != 4 or {item.version_number for item in plan.items} != {2}:
            raise ValueError("Rule v2 requires four immutable version-2 plan items")
        return RuleScoringCalculator.calculate(plan, results)
