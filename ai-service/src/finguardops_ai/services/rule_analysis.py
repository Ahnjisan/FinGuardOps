"""Application service composing the existing Rule v1 execution path."""

from dataclasses import dataclass
from functools import lru_cache

from finguardops_ai.mappers import RuleAnalysisRequestMapper
from finguardops_ai.rules.v1 import (
    RuleAnalysisResult,
    RuleEvidenceTransformer,
    RuleExecutionOrchestrator,
    RuleExecutionPlanBuilder,
    RuleExecutionPlanRunner,
    RuleScoringCalculator,
    create_default_rule_evaluator_registry,
)
from finguardops_ai.schemas.rule_analysis import RuleAnalysisRequest


@dataclass(frozen=True, slots=True)
class RuleAnalysisService:
    plan_builder: RuleExecutionPlanBuilder
    plan_runner: RuleExecutionPlanRunner

    def analyze(self, request: RuleAnalysisRequest) -> RuleAnalysisResult:
        execution_input = RuleAnalysisRequestMapper.to_domain(request)
        plan = self.plan_builder.build(
            execution_input.evaluation_cutoff_at,
            execution_input.rule_versions,
        )
        planned_results = self.plan_runner.execute(plan, execution_input.rule_input)
        scoring_result = RuleScoringCalculator.calculate(plan, planned_results)
        return RuleEvidenceTransformer.transform(plan, planned_results, scoring_result)


@lru_cache(maxsize=1)
def get_rule_analysis_service() -> RuleAnalysisService:
    registry = create_default_rule_evaluator_registry()
    return RuleAnalysisService(
        plan_builder=RuleExecutionPlanBuilder(registry),
        plan_runner=RuleExecutionPlanRunner(RuleExecutionOrchestrator(registry)),
    )
