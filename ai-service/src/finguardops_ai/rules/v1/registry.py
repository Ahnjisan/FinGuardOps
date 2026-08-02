from collections.abc import Callable, Mapping, Sequence
from dataclasses import dataclass
from types import MappingProxyType

from finguardops_ai.rules.v1.models import (
    R001Facts,
    R002Facts,
    R003Facts,
    R004Facts,
    RuleEvaluationInput,
    RuleEvaluationResult,
    RuleId,
)
from finguardops_ai.rules.v1.r001 import evaluate_r001
from finguardops_ai.rules.v1.r002 import evaluate_r002
from finguardops_ai.rules.v1.r003 import evaluate_r003
from finguardops_ai.rules.v1.r004 import evaluate_r004

type RuleEvaluatorResult = (
    RuleEvaluationResult[R001Facts]
    | RuleEvaluationResult[R002Facts]
    | RuleEvaluationResult[R003Facts]
    | RuleEvaluationResult[R004Facts]
)
type RuleEvaluator = Callable[[RuleEvaluationInput], RuleEvaluatorResult]


class DuplicateRuleIdError(ValueError):
    """Raised when the same internal Rule capability ID is registered more than once."""

    def __init__(self, rule_id: RuleId) -> None:
        self.rule_id = rule_id
        super().__init__(f"Duplicate Rule ID registration: {rule_id!r}")


class UnsupportedRuleIdError(LookupError):
    """Raised when no evaluator is registered for the exact Rule ID supplied."""

    def __init__(self, rule_id: str) -> None:
        self.rule_id = rule_id
        super().__init__(f"Unsupported Rule ID: {rule_id!r}")


@dataclass(frozen=True, slots=True, init=False)
class RuleEvaluatorRegistry:
    """Immutable lookup of internal Rule capability IDs to pure evaluators.

    Rule IDs identify evaluator capabilities implemented by the AI Service. They are
    not persisted ruleCode values and do not represent the active Rule set.
    """

    _evaluators: Mapping[str, RuleEvaluator]
    _supported_rule_ids: tuple[RuleId, ...]

    def __init__(
        self,
        registrations: Sequence[tuple[RuleId, RuleEvaluator]],
    ) -> None:
        registrations_snapshot = tuple(registrations)
        evaluators: dict[str, RuleEvaluator] = {}
        supported_rule_ids: list[RuleId] = []

        for rule_id, evaluator in registrations_snapshot:
            if rule_id in evaluators:
                raise DuplicateRuleIdError(rule_id)
            evaluators[rule_id] = evaluator
            supported_rule_ids.append(rule_id)

        object.__setattr__(self, "_evaluators", MappingProxyType(dict(evaluators)))
        object.__setattr__(self, "_supported_rule_ids", tuple(supported_rule_ids))

    @property
    def supported_rule_ids(self) -> tuple[RuleId, ...]:
        return self._supported_rule_ids

    def get_evaluator(self, rule_id: str) -> RuleEvaluator:
        try:
            return self._evaluators[rule_id]
        except KeyError:
            raise UnsupportedRuleIdError(rule_id) from None


def create_default_rule_evaluator_registry() -> RuleEvaluatorRegistry:
    """Create the immutable registry of evaluator capabilities supported by Rule v1."""
    return RuleEvaluatorRegistry(
        (
            (RuleId.R001, evaluate_r001),
            (RuleId.R002, evaluate_r002),
            (RuleId.R003, evaluate_r003),
            (RuleId.R004, evaluate_r004),
        )
    )
