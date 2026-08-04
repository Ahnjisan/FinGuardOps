from dataclasses import dataclass
from enum import StrEnum

from finguardops_ai.rules.v1.execution_plan import (
    RuleExecutionPlan,
    RuleExecutionPlanItem,
)
from finguardops_ai.rules.v1.models import RuleEvaluationResult, RuleId
from finguardops_ai.rules.v1.runner import PlannedRuleResult


class ScoringGroupId(StrEnum):
    AMOUNT = "amount"
    SECURITY = "security"


class RiskLevel(StrEnum):
    LOW = "LOW"
    MEDIUM = "MEDIUM"
    HIGH = "HIGH"
    CRITICAL = "CRITICAL"


class RuleScoringErrorCategory(StrEnum):
    INVALID_SCORING_INPUT = "INVALID_SCORING_INPUT"
    SCORING_PLAN_RESULT_MISMATCH = "SCORING_PLAN_RESULT_MISMATCH"
    UNSUPPORTED_SCORING_RULE = "UNSUPPORTED_SCORING_RULE"
    INVALID_RULE_WEIGHT = "INVALID_RULE_WEIGHT"
    INVALID_SCORING_POLICY = "INVALID_SCORING_POLICY"


class RuleScoringError(ValueError):
    """A categorized failure to calculate a Rule scoring result."""

    def __init__(self, category: RuleScoringErrorCategory, message: str) -> None:
        self.category = category
        super().__init__(message)


@dataclass(frozen=True, slots=True)
class RuleScoreContribution:
    rule_id: RuleId
    execution_order: int
    matched: bool
    original_contribution: int


@dataclass(frozen=True, slots=True)
class RuleScoreGroupSummary:
    group_id: ScoringGroupId
    raw_score: int
    cap: int
    applied_score: int
    reduction: int


@dataclass(frozen=True, slots=True)
class RuleScoringResult:
    scoring_policy_version: str
    risk_score: int
    risk_level: RiskLevel
    rule_contributions: tuple[RuleScoreContribution, ...]
    group_summaries: tuple[RuleScoreGroupSummary, ...]

    def __post_init__(self) -> None:
        if not isinstance(self.rule_contributions, tuple):
            raise TypeError("rule_contributions must be a tuple")
        if not all(
            isinstance(contribution, RuleScoreContribution)
            for contribution in self.rule_contributions
        ):
            raise TypeError("rule_contributions must contain only RuleScoreContribution values")
        if not isinstance(self.group_summaries, tuple):
            raise TypeError("group_summaries must be a tuple")
        if not all(isinstance(summary, RuleScoreGroupSummary) for summary in self.group_summaries):
            raise TypeError("group_summaries must contain only RuleScoreGroupSummary values")


@dataclass(frozen=True, slots=True)
class _RuleScoringBinding:
    rule_id: RuleId
    group_id: ScoringGroupId
    canonical_weight: int


@dataclass(frozen=True, slots=True)
class _RiskBoundary:
    risk_level: RiskLevel
    minimum_score: int
    maximum_score: int


@dataclass(frozen=True, slots=True)
class _ScoringPolicy:
    version: str
    rule_bindings: tuple[_RuleScoringBinding, ...]
    group_caps: tuple[tuple[ScoringGroupId, int], ...]
    final_score_cap: int
    risk_boundaries: tuple[_RiskBoundary, ...]


_EXPECTED_POLICY_VERSION = "scoring-policy-v1"
_EXPECTED_RULE_BINDINGS = (
    _RuleScoringBinding(RuleId.R001, ScoringGroupId.AMOUNT, 15),
    _RuleScoringBinding(RuleId.R002, ScoringGroupId.SECURITY, 20),
    _RuleScoringBinding(RuleId.R003, ScoringGroupId.SECURITY, 40),
    _RuleScoringBinding(RuleId.R004, ScoringGroupId.SECURITY, 10),
)
_EXPECTED_GROUP_CAPS = (
    (ScoringGroupId.AMOUNT, 15),
    (ScoringGroupId.SECURITY, 60),
)
_EXPECTED_FINAL_SCORE_CAP = 100
_EXPECTED_RISK_BOUNDARIES = (
    _RiskBoundary(RiskLevel.LOW, 0, 19),
    _RiskBoundary(RiskLevel.MEDIUM, 20, 49),
    _RiskBoundary(RiskLevel.HIGH, 50, 79),
    _RiskBoundary(RiskLevel.CRITICAL, 80, 100),
)

_SCORING_POLICY = _ScoringPolicy(
    version="scoring-policy-v1",
    rule_bindings=(
        _RuleScoringBinding(RuleId.R001, ScoringGroupId.AMOUNT, 15),
        _RuleScoringBinding(RuleId.R002, ScoringGroupId.SECURITY, 20),
        _RuleScoringBinding(RuleId.R003, ScoringGroupId.SECURITY, 40),
        _RuleScoringBinding(RuleId.R004, ScoringGroupId.SECURITY, 10),
    ),
    group_caps=(
        (ScoringGroupId.AMOUNT, 15),
        (ScoringGroupId.SECURITY, 60),
    ),
    final_score_cap=100,
    risk_boundaries=(
        _RiskBoundary(RiskLevel.LOW, 0, 19),
        _RiskBoundary(RiskLevel.MEDIUM, 20, 49),
        _RiskBoundary(RiskLevel.HIGH, 50, 79),
        _RiskBoundary(RiskLevel.CRITICAL, 80, 100),
    ),
)


class RuleScoringCalculator:
    """Calculate deterministic Rule v1 scores from validated Runner results."""

    @staticmethod
    def calculate(
        plan: RuleExecutionPlan,
        planned_results: tuple[PlannedRuleResult, ...],
    ) -> RuleScoringResult:
        return _calculate(plan, planned_results, _SCORING_POLICY)


def _calculate(
    plan: RuleExecutionPlan,
    planned_results: tuple[PlannedRuleResult, ...],
    policy: _ScoringPolicy,
) -> RuleScoringResult:
    _validate_scoring_inputs(plan, planned_results)
    _validate_policy(policy)

    contributions: list[RuleScoreContribution] = []
    amount_raw_score = 0
    security_raw_score = 0

    for plan_item, planned_result in zip(plan.items, planned_results, strict=True):
        binding = _binding_for(plan_item.rule_id, policy)
        original_contribution = plan_item.weight if planned_result.evaluation_result.matched else 0
        contributions.append(
            RuleScoreContribution(
                rule_id=plan_item.rule_id,
                execution_order=plan_item.execution_order,
                matched=planned_result.evaluation_result.matched,
                original_contribution=original_contribution,
            )
        )
        if binding.group_id is ScoringGroupId.AMOUNT:
            amount_raw_score += original_contribution
        else:
            security_raw_score += original_contribution

    amount_summary = _summarize_group(
        ScoringGroupId.AMOUNT,
        amount_raw_score,
        policy,
    )
    security_summary = _summarize_group(
        ScoringGroupId.SECURITY,
        security_raw_score,
        policy,
    )
    risk_score = min(
        policy.final_score_cap,
        amount_summary.applied_score + security_summary.applied_score,
    )

    return RuleScoringResult(
        scoring_policy_version=policy.version,
        risk_score=risk_score,
        risk_level=_risk_level_for_score(risk_score, policy),
        rule_contributions=tuple(contributions),
        group_summaries=(amount_summary, security_summary),
    )


def _validate_scoring_inputs(
    plan: object,
    planned_results: object,
) -> None:
    if not isinstance(plan, RuleExecutionPlan):
        _raise_scoring_error(
            RuleScoringErrorCategory.INVALID_SCORING_INPUT,
            "plan must be a RuleExecutionPlan",
        )
    if not isinstance(planned_results, tuple) or not planned_results:
        _raise_scoring_error(
            RuleScoringErrorCategory.INVALID_SCORING_INPUT,
            "planned_results must be a non-empty tuple",
        )
    for index, planned_result in enumerate(planned_results):
        if not isinstance(planned_result, PlannedRuleResult):
            _raise_scoring_error(
                RuleScoringErrorCategory.INVALID_SCORING_INPUT,
                f"planned_results[{index}] must be a PlannedRuleResult",
            )

    if not isinstance(plan.items, tuple) or not plan.items:
        _raise_scoring_error(
            RuleScoringErrorCategory.INVALID_SCORING_INPUT,
            "plan.items must be a non-empty tuple",
        )
    for index, plan_item in enumerate(plan.items):
        if not isinstance(plan_item, RuleExecutionPlanItem):
            _raise_scoring_error(
                RuleScoringErrorCategory.INVALID_SCORING_INPUT,
                f"plan.items[{index}] must be a RuleExecutionPlanItem",
            )

    if len(plan.items) != len(planned_results):
        _raise_scoring_error(
            RuleScoringErrorCategory.SCORING_PLAN_RESULT_MISMATCH,
            "planned result count must equal plan item count",
        )

    previous_rule_index = -1
    seen_rule_ids: set[RuleId] = set()
    for index, (plan_item, planned_result) in enumerate(
        zip(plan.items, planned_results, strict=True)
    ):
        if planned_result.plan_item != plan_item:
            _raise_scoring_error(
                RuleScoringErrorCategory.SCORING_PLAN_RESULT_MISMATCH,
                f"planned_results[{index}].plan_item must equal plan.items[{index}]",
            )
        if type(plan_item.execution_order) is not int or plan_item.execution_order != index + 1:
            _raise_scoring_error(
                RuleScoringErrorCategory.SCORING_PLAN_RESULT_MISMATCH,
                f"plan.items[{index}].execution_order must equal {index + 1}",
            )

        binding = _find_canonical_binding(plan_item.rule_id)
        if binding is None:
            _raise_scoring_error(
                RuleScoringErrorCategory.UNSUPPORTED_SCORING_RULE,
                f"Rule ID {plan_item.rule_id!r} is not supported by scoring-policy-v1",
            )
        if plan_item.rule_id in seen_rule_ids:
            _raise_scoring_error(
                RuleScoringErrorCategory.INVALID_SCORING_INPUT,
                f"plan.items must not contain duplicate Rule ID: {plan_item.rule_id!r}",
            )
        seen_rule_ids.add(plan_item.rule_id)
        rule_index = _EXPECTED_RULE_BINDINGS.index(binding)
        if rule_index <= previous_rule_index:
            _raise_scoring_error(
                RuleScoringErrorCategory.INVALID_SCORING_INPUT,
                "plan.items Rule IDs must be in canonical order",
            )
        previous_rule_index = rule_index

        if type(plan_item.weight) is not int or not 1 <= plan_item.weight <= 100:
            _raise_scoring_error(
                RuleScoringErrorCategory.INVALID_RULE_WEIGHT,
                f"plan.items[{index}].weight must be an integer from 1 to 100",
            )
        if plan_item.weight != binding.canonical_weight:
            _raise_scoring_error(
                RuleScoringErrorCategory.INVALID_RULE_WEIGHT,
                f"plan.items[{index}].weight must equal the canonical weight "
                f"{binding.canonical_weight} for {binding.rule_id}",
            )

        evaluation_result = planned_result.evaluation_result
        if not isinstance(evaluation_result, RuleEvaluationResult):
            _raise_scoring_error(
                RuleScoringErrorCategory.INVALID_SCORING_INPUT,
                f"planned_results[{index}].evaluation_result must be a RuleEvaluationResult",
            )
        if evaluation_result.rule_id is not plan_item.rule_id:
            _raise_scoring_error(
                RuleScoringErrorCategory.SCORING_PLAN_RESULT_MISMATCH,
                f"planned_results[{index}] Rule ID must equal plan.items[{index}].rule_id",
            )
        if type(evaluation_result.matched) is not bool:
            _raise_scoring_error(
                RuleScoringErrorCategory.INVALID_SCORING_INPUT,
                f"planned_results[{index}].evaluation_result.matched must be a bool",
            )


def _validate_policy(policy: object) -> None:
    if not _policy_matches_expected_definition(policy):
        _raise_scoring_error(
            RuleScoringErrorCategory.INVALID_SCORING_POLICY,
            "scoring policy binding must exactly match scoring-policy-v1",
        )


def _policy_matches_expected_definition(policy: object) -> bool:
    if type(policy) is not _ScoringPolicy:
        return False
    if type(policy.version) is not str or policy.version != _EXPECTED_POLICY_VERSION:
        return False
    if type(policy.final_score_cap) is not int:
        return False
    if policy.final_score_cap != _EXPECTED_FINAL_SCORE_CAP:
        return False
    return (
        _rule_bindings_match_expected(policy.rule_bindings)
        and _group_caps_match_expected(policy.group_caps)
        and _risk_boundaries_match_expected(policy.risk_boundaries)
    )


def _rule_bindings_match_expected(bindings: object) -> bool:
    if type(bindings) is not tuple or len(bindings) != len(_EXPECTED_RULE_BINDINGS):
        return False
    for actual, expected in zip(bindings, _EXPECTED_RULE_BINDINGS, strict=True):
        if type(actual) is not _RuleScoringBinding:
            return False
        if type(actual.rule_id) is not RuleId or actual.rule_id is not expected.rule_id:
            return False
        if type(actual.group_id) is not ScoringGroupId or actual.group_id is not expected.group_id:
            return False
        if type(actual.canonical_weight) is not int:
            return False
        if actual.canonical_weight != expected.canonical_weight:
            return False
    return True


def _group_caps_match_expected(group_caps: object) -> bool:
    if type(group_caps) is not tuple or len(group_caps) != len(_EXPECTED_GROUP_CAPS):
        return False
    for actual, expected in zip(group_caps, _EXPECTED_GROUP_CAPS, strict=True):
        if type(actual) is not tuple or len(actual) != 2:
            return False
        actual_group_id, actual_cap = actual
        expected_group_id, expected_cap = expected
        if type(actual_group_id) is not ScoringGroupId or actual_group_id is not expected_group_id:
            return False
        if type(actual_cap) is not int or actual_cap != expected_cap:
            return False
    return True


def _risk_boundaries_match_expected(boundaries: object) -> bool:
    if type(boundaries) is not tuple or len(boundaries) != len(_EXPECTED_RISK_BOUNDARIES):
        return False
    for actual, expected in zip(boundaries, _EXPECTED_RISK_BOUNDARIES, strict=True):
        if type(actual) is not _RiskBoundary:
            return False
        if type(actual.risk_level) is not RiskLevel or actual.risk_level is not expected.risk_level:
            return False
        if type(actual.minimum_score) is not int or type(actual.maximum_score) is not int:
            return False
        if (
            actual.minimum_score != expected.minimum_score
            or actual.maximum_score != expected.maximum_score
        ):
            return False
    return True


def _find_canonical_binding(rule_id: object) -> _RuleScoringBinding | None:
    for binding in _EXPECTED_RULE_BINDINGS:
        if binding.rule_id is rule_id:
            return binding
    return None


def _binding_for(rule_id: RuleId, policy: _ScoringPolicy) -> _RuleScoringBinding:
    for binding in policy.rule_bindings:
        if binding.rule_id is rule_id:
            return binding
    _raise_scoring_error(
        RuleScoringErrorCategory.INVALID_SCORING_POLICY,
        f"scoring policy has no binding for {rule_id}",
    )


def _group_cap(group_id: ScoringGroupId, policy: _ScoringPolicy) -> int:
    for policy_group_id, cap in policy.group_caps:
        if policy_group_id is group_id:
            return cap
    _raise_scoring_error(
        RuleScoringErrorCategory.INVALID_SCORING_POLICY,
        f"scoring policy has no cap for {group_id}",
    )


def _summarize_group(
    group_id: ScoringGroupId,
    raw_score: int,
    policy: _ScoringPolicy,
) -> RuleScoreGroupSummary:
    cap = _group_cap(group_id, policy)
    applied_score = min(cap, raw_score)
    return RuleScoreGroupSummary(
        group_id=group_id,
        raw_score=raw_score,
        cap=cap,
        applied_score=applied_score,
        reduction=raw_score - applied_score,
    )


def _risk_level_for_score(score: int, policy: _ScoringPolicy) -> RiskLevel:
    for boundary in policy.risk_boundaries:
        if boundary.minimum_score <= score <= boundary.maximum_score:
            return boundary.risk_level
    _raise_scoring_error(
        RuleScoringErrorCategory.INVALID_SCORING_POLICY,
        f"scoring policy has no risk level boundary for score {score}",
    )


def _raise_scoring_error(category: RuleScoringErrorCategory, message: str) -> None:
    raise RuleScoringError(category, message)
