from dataclasses import dataclass, fields
from datetime import datetime, timedelta
from decimal import Decimal
from enum import StrEnum
from uuid import RFC_4122, UUID

from finguardops_ai.rules.v1.condition_definitions import (
    R001ConditionDefinition,
    R002ConditionDefinition,
    R003ConditionDefinition,
    R004ConditionDefinition,
)
from finguardops_ai.rules.v1.execution_plan import (
    RuleExecutionPlan,
    RuleExecutionPlanItem,
    _create_rule_set_version,
)
from finguardops_ai.rules.v1.models import (
    R001Facts,
    R002Facts,
    R003Facts,
    R004Facts,
    RuleEvaluationResult,
    RuleId,
)
from finguardops_ai.rules.v1.runner import PlannedRuleResult
from finguardops_ai.rules.v1.scoring import (
    RiskLevel,
    RuleScoreContribution,
    RuleScoreGroupSummary,
    RuleScoringCalculator,
    RuleScoringError,
    RuleScoringErrorCategory,
    RuleScoringResult,
    ScoringGroupId,
)

__all__ = [
    "RuleAnalysisResult",
    "RuleEvidenceError",
    "RuleEvidenceErrorCategory",
    "RuleEvidenceObservation",
    "RuleEvidenceOutput",
    "RuleEvidenceTransformer",
]


class RuleEvidenceErrorCategory(StrEnum):
    INVALID_RULE_EVIDENCE_INPUT = "INVALID_RULE_EVIDENCE_INPUT"
    RULE_EVIDENCE_PLAN_RESULT_MISMATCH = "RULE_EVIDENCE_PLAN_RESULT_MISMATCH"
    RULE_EVIDENCE_SCORING_MISMATCH = "RULE_EVIDENCE_SCORING_MISMATCH"
    UNSUPPORTED_RULE_EVIDENCE = "UNSUPPORTED_RULE_EVIDENCE"
    INVALID_RULE_EVIDENCE_METADATA = "INVALID_RULE_EVIDENCE_METADATA"
    INVALID_RULE_EVIDENCE_FACTS = "INVALID_RULE_EVIDENCE_FACTS"
    INVALID_RULE_EVIDENCE_TIME = "INVALID_RULE_EVIDENCE_TIME"


class RuleEvidenceError(ValueError):
    """A categorized Rule v1 Evidence transformation contract failure."""

    def __init__(self, category: RuleEvidenceErrorCategory, message: str) -> None:
        self.category = category
        super().__init__(message)


@dataclass(frozen=True, slots=True)
class RuleEvidenceObservation:
    """Immutable public base type for one Rule-specific observation."""


@dataclass(frozen=True, slots=True)
class _R001EvidenceObservation(RuleEvidenceObservation):
    observed_amount: Decimal
    amount_threshold: Decimal


@dataclass(frozen=True, slots=True)
class _R002EvidenceObservation(RuleEvidenceObservation):
    observed_amount: Decimal
    amount_threshold: Decimal
    event_id: UUID
    device_registered_at: datetime
    elapsed_seconds: int
    window_seconds: int


@dataclass(frozen=True, slots=True)
class _R003EvidenceObservation(RuleEvidenceObservation):
    observed_amount: Decimal
    amount_threshold: Decimal
    password_changed_event_id: UUID
    password_changed_at: datetime
    transfer_limit_changed_event_id: UUID
    transfer_limit_changed_at: datetime
    elapsed_seconds: int
    window_seconds: int


@dataclass(frozen=True, slots=True)
class _R004EvidenceObservation(RuleEvidenceObservation):
    observed_amount: Decimal
    event_id: UUID
    beneficiary_registered_at: datetime
    elapsed_seconds: int
    window_seconds: int


@dataclass(frozen=True, slots=True)
class RuleEvidenceOutput:
    rule_id: RuleId
    rule_version_id: UUID
    rule_code: str
    rule_version: str
    reason_code: str
    execution_order: int
    score_contribution: int
    observation_summary: RuleEvidenceObservation
    evidence_occurred_at: datetime


@dataclass(frozen=True, slots=True)
class RuleAnalysisResult:
    evaluation_cutoff_at: datetime
    rule_set_version: str
    scoring_result: RuleScoringResult
    evidence: tuple[RuleEvidenceOutput, ...]

    def __post_init__(self) -> None:
        if type(self.scoring_result) is not RuleScoringResult:
            raise TypeError("scoring_result must be a RuleScoringResult")
        if type(self.evidence) is not tuple:
            raise TypeError("evidence must be a tuple")
        if not all(type(item) is RuleEvidenceOutput for item in self.evidence):
            raise TypeError("evidence must contain only RuleEvidenceOutput values")


_RULE_CODES = {
    RuleId.R001: "TRANSFER_ABSOLUTE_HIGH_AMOUNT",
    RuleId.R002: "RECENT_DEVICE_REGISTRATION_HIGH_AMOUNT",
    RuleId.R003: "RECENT_SECURITY_CHANGE_HIGH_AMOUNT",
    RuleId.R004: "RECENT_BENEFICIARY_TRANSFER",
}
_FACT_TYPES = {
    RuleId.R001: R001Facts,
    RuleId.R002: R002Facts,
    RuleId.R003: R003Facts,
    RuleId.R004: R004Facts,
}
_CONDITION_TYPES = {
    RuleId.R001: R001ConditionDefinition,
    RuleId.R002: R002ConditionDefinition,
    RuleId.R003: R003ConditionDefinition,
    RuleId.R004: R004ConditionDefinition,
}
_FACT_FIELDS = {
    RuleId.R001: ("observed_amount", "amount_threshold"),
    RuleId.R002: (
        "observed_amount",
        "amount_threshold",
        "event_id",
        "device_registered_at",
        "elapsed_seconds",
        "window_seconds",
    ),
    RuleId.R003: (
        "observed_amount",
        "amount_threshold",
        "password_changed_event_id",
        "password_changed_at",
        "transfer_limit_changed_event_id",
        "transfer_limit_changed_at",
        "elapsed_seconds",
        "window_seconds",
    ),
    RuleId.R004: (
        "observed_amount",
        "event_id",
        "beneficiary_registered_at",
        "elapsed_seconds",
        "window_seconds",
    ),
}
_CANONICAL_RULE_ORDER = (RuleId.R001, RuleId.R002, RuleId.R003, RuleId.R004)


class RuleEvidenceTransformer:
    """Transform validated Rule execution and scoring values into immutable Evidence."""

    @staticmethod
    def transform(
        plan: RuleExecutionPlan,
        planned_results: tuple[PlannedRuleResult, ...],
        scoring_result: RuleScoringResult,
    ) -> RuleAnalysisResult:
        _validate_input_structure(plan, planned_results, scoring_result)
        _validate_plan_result_alignment(plan, planned_results)
        _validate_rule_identity_and_order(plan, planned_results)
        _validate_scoring_alignment(plan, planned_results, scoring_result)
        _validate_metadata(plan)
        _validate_unmatched_facts(planned_results)

        evidence = tuple(
            _transform_matched_rule(plan, planned_result, contribution)
            for planned_result, contribution in zip(
                planned_results,
                scoring_result.rule_contributions,
                strict=True,
            )
            if planned_result.evaluation_result.matched
        )
        return RuleAnalysisResult(
            evaluation_cutoff_at=plan.evaluation_cutoff_at,
            rule_set_version=plan.rule_set_version,
            scoring_result=scoring_result,
            evidence=evidence,
        )


def _validate_input_structure(
    plan: object,
    planned_results: object,
    scoring_result: object,
) -> None:
    if type(plan) is not RuleExecutionPlan:
        _invalid_input("plan must be a RuleExecutionPlan")
    if type(planned_results) is not tuple or not planned_results:
        _invalid_input("planned_results must be a non-empty tuple")
    if type(scoring_result) is not RuleScoringResult:
        _invalid_input("scoring_result must be a RuleScoringResult")

    if type(plan.items) is not tuple or not plan.items:
        _invalid_input("plan.items must be a non-empty tuple")
    for index, plan_item in enumerate(plan.items):
        if type(plan_item) is not RuleExecutionPlanItem:
            _invalid_input(f"plan.items[{index}] must be a RuleExecutionPlanItem")

    for index, planned_result in enumerate(planned_results):
        if type(planned_result) is not PlannedRuleResult:
            _invalid_input(f"planned_results[{index}] must be a PlannedRuleResult")
        if type(planned_result.evaluation_result) is not RuleEvaluationResult:
            _invalid_input(
                f"planned_results[{index}].evaluation_result must be a RuleEvaluationResult"
            )

    contributions = scoring_result.rule_contributions
    if type(contributions) is not tuple:
        _invalid_input("scoring_result.rule_contributions must be a tuple")
    for index, contribution in enumerate(contributions):
        if type(contribution) is not RuleScoreContribution:
            _invalid_input(
                f"scoring_result.rule_contributions[{index}] must be a RuleScoreContribution"
            )

    group_summaries = scoring_result.group_summaries
    if type(group_summaries) is not tuple:
        _invalid_input("scoring_result.group_summaries must be a tuple")
    for index, summary in enumerate(group_summaries):
        if type(summary) is not RuleScoreGroupSummary:
            _invalid_input(
                f"scoring_result.group_summaries[{index}] must be a RuleScoreGroupSummary"
            )


def _validate_plan_result_alignment(
    plan: RuleExecutionPlan,
    planned_results: tuple[PlannedRuleResult, ...],
) -> None:
    if len(plan.items) != len(planned_results):
        _plan_result_mismatch("planned result count must equal plan item count")
    for index, (plan_item, planned_result) in enumerate(
        zip(plan.items, planned_results, strict=True)
    ):
        if planned_result.plan_item != plan_item:
            _plan_result_mismatch(
                f"planned_results[{index}].plan_item must equal plan.items[{index}]"
            )


def _validate_rule_identity_and_order(
    plan: RuleExecutionPlan,
    planned_results: tuple[PlannedRuleResult, ...],
) -> None:
    seen_rule_ids: set[RuleId] = set()
    previous_rule_index = -1
    for index, (plan_item, planned_result) in enumerate(
        zip(plan.items, planned_results, strict=True)
    ):
        rule_id = plan_item.rule_id
        if type(rule_id) is not RuleId:
            if isinstance(rule_id, str) and rule_id in {member.value for member in RuleId}:
                _invalid_metadata(f"plan.items[{index}].rule_id must be a canonical RuleId")
            _unsupported(f"Unsupported Rule ID at plan.items[{index}]: {rule_id!r}")
        if rule_id not in _RULE_CODES:
            _unsupported(f"Unsupported Rule ID at plan.items[{index}]: {rule_id!r}")
        if rule_id in seen_rule_ids:
            _invalid_metadata(f"plan.items contains duplicate Rule ID: {rule_id!r}")
        seen_rule_ids.add(rule_id)

        rule_index = _CANONICAL_RULE_ORDER.index(rule_id)
        if rule_index <= previous_rule_index:
            _plan_result_mismatch("plan.items Rule IDs must remain in canonical order")
        previous_rule_index = rule_index

        if type(plan_item.execution_order) is not int or plan_item.execution_order != index + 1:
            _plan_result_mismatch(f"plan.items[{index}].execution_order must equal {index + 1}")
        evaluation_result = planned_result.evaluation_result
        if evaluation_result.rule_id is not rule_id:
            _plan_result_mismatch(
                f"planned_results[{index}] Rule ID must equal plan.items[{index}].rule_id"
            )


def _validate_scoring_alignment(
    plan: RuleExecutionPlan,
    planned_results: tuple[PlannedRuleResult, ...],
    scoring_result: RuleScoringResult,
) -> None:
    _validate_scoring_result_fields(scoring_result)
    contributions = scoring_result.rule_contributions
    if len(contributions) != len(plan.items):
        _scoring_mismatch("scoring contribution count must equal plan item count")

    for index, planned_result in enumerate(planned_results):
        if type(planned_result.evaluation_result.matched) is not bool:
            _invalid_facts(f"planned_results[{index}].matched must be a bool")

    try:
        canonical_result = RuleScoringCalculator.calculate(plan, planned_results)
    except RuleScoringError as exc:
        category = _map_scoring_error(exc.category)
        raise RuleEvidenceError(category, "Rule scoring inputs are not canonical") from exc

    for index, (plan_item, planned_result, contribution) in enumerate(
        zip(plan.items, planned_results, contributions, strict=True)
    ):
        matched = planned_result.evaluation_result.matched
        if contribution.rule_id is not plan_item.rule_id:
            _scoring_mismatch(
                f"rule_contributions[{index}].rule_id must equal plan.items[{index}].rule_id"
            )
        if (
            type(contribution.execution_order) is not int
            or contribution.execution_order != plan_item.execution_order
        ):
            _scoring_mismatch(f"rule_contributions[{index}].execution_order must match the plan")
        if type(contribution.matched) is not bool or contribution.matched is not matched:
            _scoring_mismatch(f"rule_contributions[{index}].matched must match evaluation result")
        expected_contribution = canonical_result.rule_contributions[index].original_contribution
        if (
            type(contribution.original_contribution) is not int
            or contribution.original_contribution != expected_contribution
        ):
            _scoring_mismatch(f"rule_contributions[{index}].original_contribution is inconsistent")

    if scoring_result != canonical_result:
        _scoring_mismatch("scoring_result must equal the canonical RuleScoringCalculator result")


def _validate_scoring_result_fields(scoring_result: RuleScoringResult) -> None:
    if type(scoring_result.scoring_policy_version) is not str:
        _scoring_mismatch("scoring_policy_version must be a string")
    if type(scoring_result.risk_score) is not int:
        _scoring_mismatch("risk_score must be an integer")
    if type(scoring_result.risk_level) is not RiskLevel:
        _scoring_mismatch("risk_level must be a RiskLevel")

    for index, contribution in enumerate(scoring_result.rule_contributions):
        if type(contribution.rule_id) is not RuleId:
            _scoring_mismatch(f"rule_contributions[{index}].rule_id must be a RuleId")
        if type(contribution.execution_order) is not int:
            _scoring_mismatch(f"rule_contributions[{index}].execution_order must be an integer")
        if type(contribution.matched) is not bool:
            _scoring_mismatch(f"rule_contributions[{index}].matched must be a bool")
        if type(contribution.original_contribution) is not int:
            _scoring_mismatch(
                f"rule_contributions[{index}].original_contribution must be an integer"
            )

    for index, summary in enumerate(scoring_result.group_summaries):
        if type(summary.group_id) is not ScoringGroupId:
            _scoring_mismatch(f"group_summaries[{index}].group_id must be a ScoringGroupId")
        for field_name in ("raw_score", "cap", "applied_score", "reduction"):
            if type(getattr(summary, field_name)) is not int:
                _scoring_mismatch(f"group_summaries[{index}].{field_name} must be an integer")


def _map_scoring_error(category: RuleScoringErrorCategory) -> RuleEvidenceErrorCategory:
    if category is RuleScoringErrorCategory.SCORING_PLAN_RESULT_MISMATCH:
        return RuleEvidenceErrorCategory.RULE_EVIDENCE_PLAN_RESULT_MISMATCH
    if category is RuleScoringErrorCategory.UNSUPPORTED_SCORING_RULE:
        return RuleEvidenceErrorCategory.UNSUPPORTED_RULE_EVIDENCE
    if category is RuleScoringErrorCategory.INVALID_RULE_WEIGHT:
        return RuleEvidenceErrorCategory.INVALID_RULE_EVIDENCE_METADATA
    if category is RuleScoringErrorCategory.INVALID_SCORING_POLICY:
        return RuleEvidenceErrorCategory.RULE_EVIDENCE_SCORING_MISMATCH
    return RuleEvidenceErrorCategory.INVALID_RULE_EVIDENCE_INPUT


def _validate_metadata(plan: RuleExecutionPlan) -> None:
    _require_utc(plan.evaluation_cutoff_at, "plan.evaluation_cutoff_at")
    for index, plan_item in enumerate(plan.items):
        prefix = f"plan.items[{index}]"
        _require_uuid_v4(
            plan_item.rule_version_id,
            f"{prefix}.rule_version_id",
            RuleEvidenceErrorCategory.INVALID_RULE_EVIDENCE_METADATA,
        )
        expected_rule_code = _RULE_CODES[plan_item.rule_id]
        if type(plan_item.rule_code) is not str or plan_item.rule_code != expected_rule_code:
            _invalid_metadata(f"{prefix}.rule_code does not match {plan_item.rule_id}")
        if type(plan_item.version_number) is not int or plan_item.version_number < 1:
            _invalid_metadata(f"{prefix}.version_number must be a positive integer")
        if type(plan_item.reason_code) is not str or plan_item.reason_code != expected_rule_code:
            _invalid_metadata(f"{prefix}.reason_code is not allowed for {plan_item.rule_id}")
        expected_condition_type = _CONDITION_TYPES[plan_item.rule_id]
        if type(plan_item.condition_definition) is not expected_condition_type:
            _invalid_metadata(f"{prefix}.condition_definition has the wrong concrete Rule type")
        _require_utc(plan_item.effective_from, f"{prefix}.effective_from")
        if plan_item.effective_to is not None:
            _require_utc(plan_item.effective_to, f"{prefix}.effective_to")
            if plan_item.effective_to <= plan_item.effective_from:
                _invalid_metadata(f"{prefix}.effective_to must be later than effective_from")
        if plan_item.effective_from > plan.evaluation_cutoff_at:
            _invalid_metadata(f"{prefix} is not effective at the evaluation cutoff")
        if (
            plan_item.effective_to is not None
            and plan.evaluation_cutoff_at >= plan_item.effective_to
        ):
            _invalid_metadata(f"{prefix} is not effective at the evaluation cutoff")

    if (
        type(plan.rule_set_version) is not str
        or len(plan.rule_set_version) != 64
        or any(character not in "0123456789abcdef" for character in plan.rule_set_version)
        or plan.rule_set_version != _create_rule_set_version(plan.items)
    ):
        _invalid_metadata("plan.rule_set_version must match the canonical plan snapshot")


def _transform_matched_rule(
    plan: RuleExecutionPlan,
    planned_result: PlannedRuleResult,
    contribution: RuleScoreContribution,
) -> RuleEvidenceOutput:
    plan_item = planned_result.plan_item
    evaluation_result = planned_result.evaluation_result
    facts = evaluation_result.facts
    expected_facts_type = _FACT_TYPES[plan_item.rule_id]
    if type(facts) is not expected_facts_type:
        _invalid_facts(f"Matched {plan_item.rule_id} must have {expected_facts_type.__name__}")
    if tuple(field.name for field in fields(facts)) != _FACT_FIELDS[plan_item.rule_id]:
        _invalid_facts(f"{plan_item.rule_id} facts fields do not match the observation allowlist")

    observation, evidence_occurred_at = _create_observation(
        plan_item,
        facts,
        plan.evaluation_cutoff_at,
    )
    if evidence_occurred_at > plan.evaluation_cutoff_at:
        _invalid_time("evidence_occurred_at must not be after evaluation_cutoff_at")
    return RuleEvidenceOutput(
        rule_id=plan_item.rule_id,
        rule_version_id=plan_item.rule_version_id,
        rule_code=plan_item.rule_code,
        rule_version=str(plan_item.version_number),
        reason_code=plan_item.reason_code,
        execution_order=plan_item.execution_order,
        score_contribution=contribution.original_contribution,
        observation_summary=observation,
        evidence_occurred_at=evidence_occurred_at,
    )


def _create_observation(
    plan_item: RuleExecutionPlanItem,
    facts: object,
    cutoff_at: datetime,
) -> tuple[RuleEvidenceObservation, datetime]:
    match plan_item.rule_id:
        case RuleId.R001:
            assert type(facts) is R001Facts
            _require_amounts(facts.observed_amount, facts.amount_threshold)
            condition = plan_item.condition_definition
            assert type(condition) is R001ConditionDefinition
            if facts.amount_threshold != condition.amount_threshold:
                _invalid_facts("R001 amount_threshold must match the RuleVersion metadata")
            return (
                _R001EvidenceObservation(
                    observed_amount=facts.observed_amount,
                    amount_threshold=facts.amount_threshold,
                ),
                cutoff_at,
            )
        case RuleId.R002:
            assert type(facts) is R002Facts
            _require_amounts(facts.observed_amount, facts.amount_threshold)
            _require_uuid_v4(
                facts.event_id,
                "R002.event_id",
                RuleEvidenceErrorCategory.INVALID_RULE_EVIDENCE_FACTS,
            )
            _validate_behavior_time(
                facts.device_registered_at,
                facts.elapsed_seconds,
                facts.window_seconds,
                cutoff_at,
                plan_item.condition_definition,
            )
            return (
                _R002EvidenceObservation(
                    observed_amount=facts.observed_amount,
                    amount_threshold=facts.amount_threshold,
                    event_id=facts.event_id,
                    device_registered_at=facts.device_registered_at,
                    elapsed_seconds=facts.elapsed_seconds,
                    window_seconds=facts.window_seconds,
                ),
                facts.device_registered_at,
            )
        case RuleId.R003:
            assert type(facts) is R003Facts
            _require_amounts(facts.observed_amount, facts.amount_threshold)
            _require_uuid_v4(
                facts.password_changed_event_id,
                "R003.password_changed_event_id",
                RuleEvidenceErrorCategory.INVALID_RULE_EVIDENCE_FACTS,
            )
            _require_uuid_v4(
                facts.transfer_limit_changed_event_id,
                "R003.transfer_limit_changed_event_id",
                RuleEvidenceErrorCategory.INVALID_RULE_EVIDENCE_FACTS,
            )
            _validate_r003_time(facts, cutoff_at, plan_item.condition_definition)
            return (
                _R003EvidenceObservation(
                    observed_amount=facts.observed_amount,
                    amount_threshold=facts.amount_threshold,
                    password_changed_event_id=facts.password_changed_event_id,
                    password_changed_at=facts.password_changed_at,
                    transfer_limit_changed_event_id=facts.transfer_limit_changed_event_id,
                    transfer_limit_changed_at=facts.transfer_limit_changed_at,
                    elapsed_seconds=facts.elapsed_seconds,
                    window_seconds=facts.window_seconds,
                ),
                facts.transfer_limit_changed_at,
            )
        case RuleId.R004:
            assert type(facts) is R004Facts
            _require_amount(facts.observed_amount, "R004.observed_amount")
            _require_uuid_v4(
                facts.event_id,
                "R004.event_id",
                RuleEvidenceErrorCategory.INVALID_RULE_EVIDENCE_FACTS,
            )
            _validate_behavior_time(
                facts.beneficiary_registered_at,
                facts.elapsed_seconds,
                facts.window_seconds,
                cutoff_at,
                plan_item.condition_definition,
            )
            return (
                _R004EvidenceObservation(
                    observed_amount=facts.observed_amount,
                    event_id=facts.event_id,
                    beneficiary_registered_at=facts.beneficiary_registered_at,
                    elapsed_seconds=facts.elapsed_seconds,
                    window_seconds=facts.window_seconds,
                ),
                facts.beneficiary_registered_at,
            )
    _unsupported(f"Unsupported Rule Evidence: {plan_item.rule_id!r}")


def _validate_unmatched_facts(planned_results: tuple[PlannedRuleResult, ...]) -> None:
    for index, planned_result in enumerate(planned_results):
        evaluation_result = planned_result.evaluation_result
        if not evaluation_result.matched and evaluation_result.facts is not None:
            _invalid_facts(f"Unmatched planned_results[{index}] must have null facts")
        if evaluation_result.matched and evaluation_result.facts is None:
            _invalid_facts(f"Matched planned_results[{index}] must have facts")


def _require_amounts(observed_amount: object, amount_threshold: object) -> None:
    _require_amount(observed_amount, "observed_amount")
    _require_amount(amount_threshold, "amount_threshold")


def _require_amount(value: object, field_name: str) -> None:
    if (
        type(value) is not Decimal
        or not value.is_finite()
        or value <= 0
        or value != value.to_integral_value()
    ):
        _invalid_facts(f"{field_name} must be a positive finite integer Decimal")


def _validate_behavior_time(
    event_at: object,
    elapsed_seconds: object,
    window_seconds: object,
    cutoff_at: datetime,
    condition_definition: object,
) -> None:
    _require_utc(event_at, "behavior evidence timestamp")
    if type(elapsed_seconds) is not int or elapsed_seconds < 0:
        _invalid_time("elapsed_seconds must be a non-negative integer")
    if type(window_seconds) is not int or window_seconds <= 0:
        _invalid_time("window_seconds must be a positive integer")
    if not isinstance(condition_definition, (R002ConditionDefinition, R004ConditionDefinition)):
        _invalid_metadata("Behavior Rule condition definition is unsupported")
    if window_seconds != condition_definition.window_seconds:
        _invalid_time("window_seconds must match the RuleVersion condition definition")
    if not cutoff_at - timedelta(seconds=window_seconds) <= event_at <= cutoff_at:
        _invalid_time("behavior evidence timestamp must be inside the inclusive window")
    if elapsed_seconds != _elapsed_seconds(event_at, cutoff_at):
        _invalid_time("elapsed_seconds must equal cutoff minus behavior evidence timestamp")


def _validate_r003_time(
    facts: R003Facts,
    cutoff_at: datetime,
    condition_definition: object,
) -> None:
    _require_utc(facts.password_changed_at, "R003.password_changed_at")
    _require_utc(facts.transfer_limit_changed_at, "R003.transfer_limit_changed_at")
    if type(facts.elapsed_seconds) is not int or facts.elapsed_seconds < 0:
        _invalid_time("R003.elapsed_seconds must be a non-negative integer")
    if type(facts.window_seconds) is not int or facts.window_seconds <= 0:
        _invalid_time("R003.window_seconds must be a positive integer")
    if type(condition_definition) is not R003ConditionDefinition:
        _invalid_metadata("R003 condition definition is unsupported")
    if facts.window_seconds != condition_definition.window_seconds:
        _invalid_time("R003.window_seconds must match the RuleVersion condition definition")
    window_start = cutoff_at - timedelta(seconds=facts.window_seconds)
    if not (
        window_start <= facts.password_changed_at <= facts.transfer_limit_changed_at <= cutoff_at
    ):
        _invalid_time(
            "R003 timestamps must satisfy window start <= password <= transfer limit <= cutoff"
        )
    if facts.elapsed_seconds != _elapsed_seconds(facts.transfer_limit_changed_at, cutoff_at):
        _invalid_time("R003.elapsed_seconds must equal cutoff minus transfer_limit_changed_at")


def _elapsed_seconds(event_at: datetime, cutoff_at: datetime) -> int:
    elapsed = cutoff_at - event_at
    return elapsed.days * 86_400 + elapsed.seconds


def _require_uuid_v4(
    value: object,
    field_name: str,
    category: RuleEvidenceErrorCategory,
) -> None:
    if type(value) is not UUID:
        _raise_evidence_error(category, f"{field_name} must be an RFC 4122 UUID v4")
    encoded_version = (value.int >> 76) & 0xF
    if encoded_version != 4:
        _raise_evidence_error(category, f"{field_name} must be an RFC 4122 UUID v4")
    if value.variant != RFC_4122:
        _raise_evidence_error(category, f"{field_name} must be an RFC 4122 UUID v4")


def _require_utc(value: object, field_name: str) -> None:
    if (
        type(value) is not datetime
        or value.tzinfo is None
        or value.utcoffset() is None
        or value.utcoffset() != timedelta(0)
    ):
        _invalid_time(f"{field_name} must be timezone-aware UTC")


def _invalid_input(message: str) -> None:
    _raise_evidence_error(RuleEvidenceErrorCategory.INVALID_RULE_EVIDENCE_INPUT, message)


def _plan_result_mismatch(message: str) -> None:
    _raise_evidence_error(
        RuleEvidenceErrorCategory.RULE_EVIDENCE_PLAN_RESULT_MISMATCH,
        message,
    )


def _scoring_mismatch(message: str) -> None:
    _raise_evidence_error(RuleEvidenceErrorCategory.RULE_EVIDENCE_SCORING_MISMATCH, message)


def _unsupported(message: str) -> None:
    _raise_evidence_error(RuleEvidenceErrorCategory.UNSUPPORTED_RULE_EVIDENCE, message)


def _invalid_metadata(message: str) -> None:
    _raise_evidence_error(RuleEvidenceErrorCategory.INVALID_RULE_EVIDENCE_METADATA, message)


def _invalid_facts(message: str) -> None:
    _raise_evidence_error(RuleEvidenceErrorCategory.INVALID_RULE_EVIDENCE_FACTS, message)


def _invalid_time(message: str) -> None:
    _raise_evidence_error(RuleEvidenceErrorCategory.INVALID_RULE_EVIDENCE_TIME, message)


def _raise_evidence_error(category: RuleEvidenceErrorCategory, message: str) -> None:
    raise RuleEvidenceError(category, message)
