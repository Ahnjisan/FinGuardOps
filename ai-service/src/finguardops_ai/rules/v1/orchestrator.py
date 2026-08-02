from collections.abc import Sequence
from dataclasses import dataclass

from finguardops_ai.rules.v1.models import RuleEvaluationInput
from finguardops_ai.rules.v1.registry import (
    RuleEvaluatorRegistry,
    RuleEvaluatorResult,
)


class InvalidRuleExecutionPlanError(ValueError):
    """Raised when an ordered Rule execution plan is invalid."""


class RuleEvaluatorResultMismatchError(RuntimeError):
    """Raised when an evaluator returns a result for a different Rule ID."""

    def __init__(self, requested_rule_id: str, returned_rule_id: object) -> None:
        self.requested_rule_id = requested_rule_id
        self.returned_rule_id = returned_rule_id
        super().__init__(
            "Rule evaluator result ID mismatch: "
            f"requested {requested_rule_id!r}, returned {returned_rule_id!r}"
        )


@dataclass(frozen=True, slots=True, init=False)
class RuleExecutionOrchestrator:
    """Validate and execute an ordered plan of Rule v1 evaluator capabilities."""

    _registry: RuleEvaluatorRegistry

    def __init__(self, registry: RuleEvaluatorRegistry) -> None:
        object.__setattr__(self, "_registry", registry)

    def execute(
        self,
        rule_ids: Sequence[str],
        rule_input: RuleEvaluationInput,
    ) -> tuple[RuleEvaluatorResult, ...]:
        if isinstance(rule_ids, (str, bytes)):
            raise InvalidRuleExecutionPlanError("rule_ids must be a non-string Sequence")
        if not isinstance(rule_ids, Sequence):
            raise InvalidRuleExecutionPlanError("rule_ids must be a Sequence")

        validated_rule_ids: list[str] = []
        for index, rule_id in enumerate(rule_ids):
            if not isinstance(rule_id, str):
                raise InvalidRuleExecutionPlanError(f"rule_ids[{index}] must be a string")
            validated_rule_ids.append(rule_id)

        rule_ids_snapshot = tuple(validated_rule_ids)
        if not rule_ids_snapshot:
            raise InvalidRuleExecutionPlanError("rule_ids must not be empty")

        seen_rule_ids: set[str] = set()
        for rule_id in rule_ids_snapshot:
            if rule_id in seen_rule_ids:
                raise InvalidRuleExecutionPlanError(
                    f"rule_ids must not contain duplicate Rule ID: {rule_id!r}"
                )
            seen_rule_ids.add(rule_id)

        evaluators = tuple(self._registry.get_evaluator(rule_id) for rule_id in rule_ids_snapshot)

        results: list[RuleEvaluatorResult] = []
        for requested_rule_id, evaluator in zip(
            rule_ids_snapshot,
            evaluators,
            strict=True,
        ):
            result = evaluator(rule_input)
            if result.rule_id != requested_rule_id:
                raise RuleEvaluatorResultMismatchError(
                    requested_rule_id=requested_rule_id,
                    returned_rule_id=result.rule_id,
                )
            results.append(result)

        return tuple(results)
