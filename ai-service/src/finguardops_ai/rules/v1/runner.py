from dataclasses import dataclass
from enum import StrEnum

from finguardops_ai.rules.v1.execution_plan import (
    RuleExecutionPlan,
    RuleExecutionPlanItem,
)
from finguardops_ai.rules.v1.models import (
    RuleEvaluationInput,
    RuleEvaluationResult,
    RuleId,
)
from finguardops_ai.rules.v1.orchestrator import (
    RuleEvaluatorResultMismatchError,
    RuleExecutionOrchestrator,
)
from finguardops_ai.rules.v1.registry import RuleEvaluatorResult, UnsupportedRuleIdError


class RuleExecutionPlanRunnerErrorCategory(StrEnum):
    INVALID_PLAN_RUNNER_INPUT = "INVALID_PLAN_RUNNER_INPUT"
    INVALID_RULE_EXECUTION_PLAN = "INVALID_RULE_EXECUTION_PLAN"
    EVALUATION_CUTOFF_MISMATCH = "EVALUATION_CUTOFF_MISMATCH"
    UNSUPPORTED_RULE_CAPABILITY = "UNSUPPORTED_RULE_CAPABILITY"
    RULE_EVALUATOR_EXECUTION_FAILED = "RULE_EVALUATOR_EXECUTION_FAILED"
    INVALID_RULE_EXECUTION_RESULT = "INVALID_RULE_EXECUTION_RESULT"
    RULE_EXECUTION_RESULT_COUNT_MISMATCH = "RULE_EXECUTION_RESULT_COUNT_MISMATCH"
    RULE_EVALUATOR_RESULT_MISMATCH = "RULE_EVALUATOR_RESULT_MISMATCH"


class RuleExecutionPlanRunnerError(ValueError):
    """A categorized failure to execute and combine a Rule execution plan."""

    def __init__(
        self,
        category: RuleExecutionPlanRunnerErrorCategory,
        message: str,
    ) -> None:
        self.category = category
        super().__init__(message)


@dataclass(frozen=True, slots=True)
class PlannedRuleResult:
    plan_item: RuleExecutionPlanItem
    evaluation_result: RuleEvaluatorResult


_CANONICAL_RULE_ORDER = (RuleId.R001, RuleId.R002, RuleId.R003, RuleId.R004)


@dataclass(frozen=True, slots=True)
class RuleExecutionPlanRunner:
    """Execute a validated Rule plan and strictly combine its ordered results."""

    orchestrator: RuleExecutionOrchestrator

    def execute(
        self,
        plan: RuleExecutionPlan,
        rule_input: RuleEvaluationInput,
    ) -> tuple[PlannedRuleResult, ...]:
        if not isinstance(plan, RuleExecutionPlan):
            _raise_runner_error(
                RuleExecutionPlanRunnerErrorCategory.INVALID_PLAN_RUNNER_INPUT,
                "plan must be a RuleExecutionPlan",
            )
        if not isinstance(rule_input, RuleEvaluationInput):
            _raise_runner_error(
                RuleExecutionPlanRunnerErrorCategory.INVALID_PLAN_RUNNER_INPUT,
                "rule_input must be a RuleEvaluationInput",
            )

        _validate_plan_items(plan.items)
        if plan.evaluation_cutoff_at != rule_input.transaction.occurred_at:
            _raise_runner_error(
                RuleExecutionPlanRunnerErrorCategory.EVALUATION_CUTOFF_MISMATCH,
                "plan evaluation cutoff must equal transaction occurred_at",
            )

        rule_ids = tuple(item.rule_id for item in plan.items)
        try:
            raw_results = self.orchestrator.execute(rule_ids, rule_input)
        except UnsupportedRuleIdError as exc:
            raise RuleExecutionPlanRunnerError(
                RuleExecutionPlanRunnerErrorCategory.UNSUPPORTED_RULE_CAPABILITY,
                f"Orchestrator does not support Rule ID {exc.rule_id!r}",
            ) from exc
        except RuleEvaluatorResultMismatchError as exc:
            raise RuleExecutionPlanRunnerError(
                RuleExecutionPlanRunnerErrorCategory.RULE_EVALUATOR_RESULT_MISMATCH,
                "Orchestrator returned a result for a different Rule ID",
            ) from exc
        except Exception as exc:
            raise RuleExecutionPlanRunnerError(
                RuleExecutionPlanRunnerErrorCategory.RULE_EVALUATOR_EXECUTION_FAILED,
                "Rule evaluator execution failed",
            ) from exc

        _validate_raw_results(plan.items, raw_results)
        return tuple(
            PlannedRuleResult(plan_item=plan_item, evaluation_result=raw_result)
            for plan_item, raw_result in zip(plan.items, raw_results, strict=True)
        )


def _validate_plan_items(items: object) -> None:
    if not isinstance(items, tuple) or not items:
        _invalid_plan("plan.items must be a non-empty tuple")

    for index, item in enumerate(items):
        if not isinstance(item, RuleExecutionPlanItem):
            _invalid_plan(f"plan.items[{index}] must be a RuleExecutionPlanItem")

    for index, item in enumerate(items):
        if type(item.execution_order) is not int or item.execution_order != index + 1:
            _invalid_plan(f"plan.items[{index}].execution_order must equal {index + 1}")

    seen_rule_ids: list[object] = []
    for item in items:
        if item.rule_id in seen_rule_ids:
            _invalid_plan(f"plan.items must not contain duplicate Rule ID: {item.rule_id!r}")
        seen_rule_ids.append(item.rule_id)

    canonical_index = -1
    for index, item in enumerate(items):
        if not isinstance(item.rule_id, RuleId):
            _invalid_plan(f"plan.items[{index}].rule_id must be a RuleId")
        next_index = _CANONICAL_RULE_ORDER.index(item.rule_id)
        if next_index <= canonical_index:
            _invalid_plan("plan.items Rule IDs must be in canonical order")
        canonical_index = next_index


def _validate_raw_results(
    plan_items: tuple[RuleExecutionPlanItem, ...],
    raw_results: object,
) -> None:
    if not isinstance(raw_results, tuple):
        _raise_runner_error(
            RuleExecutionPlanRunnerErrorCategory.INVALID_RULE_EXECUTION_RESULT,
            "Orchestrator result collection must be a tuple",
        )

    for index, raw_result in enumerate(raw_results):
        if not isinstance(raw_result, RuleEvaluationResult):
            _raise_runner_error(
                RuleExecutionPlanRunnerErrorCategory.INVALID_RULE_EXECUTION_RESULT,
                f"Orchestrator result[{index}] must be a RuleEvaluationResult",
            )

    if len(raw_results) != len(plan_items):
        _raise_runner_error(
            RuleExecutionPlanRunnerErrorCategory.RULE_EXECUTION_RESULT_COUNT_MISMATCH,
            "Orchestrator result count must equal plan item count",
        )

    for index, (plan_item, raw_result) in enumerate(zip(plan_items, raw_results, strict=True)):
        if plan_item.rule_id != raw_result.rule_id:
            _raise_runner_error(
                RuleExecutionPlanRunnerErrorCategory.RULE_EVALUATOR_RESULT_MISMATCH,
                "Rule evaluator result ID mismatch at index "
                f"{index}: expected {plan_item.rule_id!r}, returned {raw_result.rule_id!r}",
            )


def _invalid_plan(message: str) -> None:
    _raise_runner_error(
        RuleExecutionPlanRunnerErrorCategory.INVALID_RULE_EXECUTION_PLAN,
        message,
    )


def _raise_runner_error(
    category: RuleExecutionPlanRunnerErrorCategory,
    message: str,
) -> None:
    raise RuleExecutionPlanRunnerError(category, message)
