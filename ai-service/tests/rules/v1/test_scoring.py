from dataclasses import FrozenInstanceError, replace
from datetime import UTC, datetime, timedelta
from typing import cast
from uuid import UUID

import pytest

import finguardops_ai.rules.v1.scoring as scoring_module
from finguardops_ai.rules.v1 import (
    BehaviorEventType,
    PlannedRuleResult,
    R004ConditionDefinition,
    RiskLevel,
    RuleEvaluationResult,
    RuleExecutionPlan,
    RuleExecutionPlanItem,
    RuleId,
    RuleScoreContribution,
    RuleScoreGroupSummary,
    RuleScoringCalculator,
    RuleScoringError,
    RuleScoringErrorCategory,
    RuleScoringResult,
    ScoringGroupId,
)

CUTOFF_AT = datetime(2026, 7, 23, 12, 0, tzinfo=UTC)
RULE_CODES = {
    RuleId.R001: "TRANSFER_ABSOLUTE_HIGH_AMOUNT",
    RuleId.R002: "RECENT_DEVICE_REGISTRATION_HIGH_AMOUNT",
    RuleId.R003: "RECENT_SECURITY_CHANGE_HIGH_AMOUNT",
    RuleId.R004: "RECENT_BENEFICIARY_TRANSFER",
}
WEIGHTS = {RuleId.R001: 15, RuleId.R002: 20, RuleId.R003: 40, RuleId.R004: 10}


def _condition() -> R004ConditionDefinition:
    return R004ConditionDefinition(
        event_type=BehaviorEventType.BENEFICIARY_REGISTERED,
        window_seconds=86400,
        match_policy="SAME_CUSTOMER_SENDER_ACCOUNT_AND_BENEFICIARY",
        selection_policy="LATEST_OCCURRED_AT_THEN_EVENT_ID_ASC",
    )


def _item(rule_id: RuleId, execution_order: int) -> RuleExecutionPlanItem:
    number = int(rule_id.value[-1])
    return RuleExecutionPlanItem(
        rule_version_id=UUID(f"20000000-0000-4000-8000-00000000000{number}"),
        rule_code=RULE_CODES[rule_id],
        rule_id=rule_id,
        version_number=1,
        reason_code=RULE_CODES[rule_id],
        weight=WEIGHTS[rule_id],
        condition_definition=_condition(),
        effective_from=CUTOFF_AT - timedelta(days=1),
        effective_to=CUTOFF_AT + timedelta(days=1),
        execution_order=execution_order,
    )


def _plan(
    rule_ids: tuple[RuleId, ...] = (RuleId.R001, RuleId.R002, RuleId.R003, RuleId.R004),
) -> RuleExecutionPlan:
    return RuleExecutionPlan(
        evaluation_cutoff_at=CUTOFF_AT,
        rule_set_version="rule-set-version",
        items=tuple(_item(rule_id, index) for index, rule_id in enumerate(rule_ids, start=1)),
    )


def _planned_results(
    plan: RuleExecutionPlan,
    matched_rule_ids: tuple[RuleId, ...] = (),
) -> tuple[PlannedRuleResult, ...]:
    return tuple(
        PlannedRuleResult(
            plan_item=item,
            evaluation_result=RuleEvaluationResult(
                rule_id=item.rule_id,
                matched=item.rule_id in matched_rule_ids,
                facts=object() if item.rule_id in matched_rule_ids else None,
            ),
        )
        for item in plan.items
    )


def _calculate(
    matched_rule_ids: tuple[RuleId, ...],
    rule_ids: tuple[RuleId, ...] = (RuleId.R001, RuleId.R002, RuleId.R003, RuleId.R004),
) -> RuleScoringResult:
    plan = _plan(rule_ids)
    return RuleScoringCalculator.calculate(plan, _planned_results(plan, matched_rule_ids))


def _assert_category(
    exc_info: pytest.ExceptionInfo[RuleScoringError],
    expected: RuleScoringErrorCategory,
) -> None:
    assert exc_info.value.category is expected


def test_scoring_error_categories_are_the_five_public_semantic_categories() -> None:
    assert set(RuleScoringErrorCategory) == {
        RuleScoringErrorCategory.INVALID_SCORING_INPUT,
        RuleScoringErrorCategory.SCORING_PLAN_RESULT_MISMATCH,
        RuleScoringErrorCategory.UNSUPPORTED_SCORING_RULE,
        RuleScoringErrorCategory.INVALID_RULE_WEIGHT,
        RuleScoringErrorCategory.INVALID_SCORING_POLICY,
    }


def test_calculator_is_the_only_public_scoring_entrypoint() -> None:
    assert callable(RuleScoringCalculator.calculate)
    assert not hasattr(scoring_module, "calculate")
    assert not hasattr(scoring_module, "RuleV1Scorer")


@pytest.mark.parametrize(
    ("matched_rule_ids", "expected_score", "expected_level"),
    (
        ((), 0, RiskLevel.LOW),
        ((RuleId.R004,), 10, RiskLevel.LOW),
        ((RuleId.R001,), 15, RiskLevel.LOW),
        ((RuleId.R001, RuleId.R002), 35, RiskLevel.MEDIUM),
        ((RuleId.R001, RuleId.R003), 55, RiskLevel.HIGH),
        ((RuleId.R001, RuleId.R004), 25, RiskLevel.MEDIUM),
        (
            (RuleId.R001, RuleId.R002, RuleId.R003, RuleId.R004),
            75,
            RiskLevel.HIGH,
        ),
    ),
)
def test_calculate_canonical_rule_v1_scores(
    matched_rule_ids: tuple[RuleId, ...],
    expected_score: int,
    expected_level: RiskLevel,
) -> None:
    result = _calculate(matched_rule_ids)

    assert result.risk_score == expected_score
    assert result.risk_level is expected_level


def test_all_rules_preserve_original_contributions_and_apply_group_cap() -> None:
    result = _calculate((RuleId.R001, RuleId.R002, RuleId.R003, RuleId.R004))

    assert tuple(
        contribution.original_contribution for contribution in result.rule_contributions
    ) == (15, 20, 40, 10)
    assert result.group_summaries == (
        RuleScoreGroupSummary(
            group_id=ScoringGroupId.AMOUNT,
            raw_score=15,
            cap=15,
            applied_score=15,
            reduction=0,
        ),
        RuleScoreGroupSummary(
            group_id=ScoringGroupId.SECURITY,
            raw_score=70,
            cap=60,
            applied_score=60,
            reduction=10,
        ),
    )


def test_result_preserves_plan_order_group_order_and_policy_version() -> None:
    rule_ids = (RuleId.R001, RuleId.R003, RuleId.R004)
    result = _calculate((RuleId.R003,), rule_ids)

    assert result.scoring_policy_version == "scoring-policy-v1"
    assert tuple(contribution.rule_id for contribution in result.rule_contributions) == rule_ids
    assert tuple(contribution.execution_order for contribution in result.rule_contributions) == (
        1,
        2,
        3,
    )
    assert tuple(summary.group_id for summary in result.group_summaries) == (
        ScoringGroupId.AMOUNT,
        ScoringGroupId.SECURITY,
    )


def test_partial_plan_returns_both_groups_without_synthetic_contributions() -> None:
    result = _calculate((RuleId.R004,), (RuleId.R004,))

    assert tuple(contribution.rule_id for contribution in result.rule_contributions) == (
        RuleId.R004,
    )
    assert result.group_summaries == (
        RuleScoreGroupSummary(ScoringGroupId.AMOUNT, 0, 15, 0, 0),
        RuleScoreGroupSummary(ScoringGroupId.SECURITY, 10, 60, 10, 0),
    )


def test_calculate_does_not_mutate_inputs_and_is_deterministic() -> None:
    plan = _plan()
    planned_results = _planned_results(plan, (RuleId.R001, RuleId.R003))
    original_plan = plan
    original_items = plan.items
    original_results = planned_results

    first = RuleScoringCalculator.calculate(plan, planned_results)
    second = RuleScoringCalculator.calculate(plan, planned_results)

    assert first == second
    assert plan is original_plan
    assert plan.items is original_items
    assert planned_results is original_results
    assert tuple(result.plan_item for result in planned_results) == plan.items


def test_returned_result_and_nested_values_are_immutable_tuples() -> None:
    result = _calculate((RuleId.R001,))

    assert isinstance(result.rule_contributions, tuple)
    assert isinstance(result.group_summaries, tuple)
    with pytest.raises(FrozenInstanceError):
        result.risk_score = 100
    with pytest.raises(FrozenInstanceError):
        result.rule_contributions[0].original_contribution = 0
    with pytest.raises(FrozenInstanceError):
        result.group_summaries[0].applied_score = 0
    with pytest.raises(TypeError):
        result.rule_contributions[0] = result.rule_contributions[0]


def test_result_model_rejects_mutable_collection_values() -> None:
    contribution = RuleScoreContribution(RuleId.R001, 1, True, 15)
    summary = RuleScoreGroupSummary(ScoringGroupId.AMOUNT, 15, 15, 15, 0)

    with pytest.raises(TypeError):
        RuleScoringResult(
            "scoring-policy-v1",
            15,
            RiskLevel.LOW,
            [contribution],  # type: ignore[arg-type]
            (summary,),
        )
    with pytest.raises(TypeError):
        RuleScoringResult(
            "scoring-policy-v1",
            15,
            RiskLevel.LOW,
            (contribution,),
            [summary],  # type: ignore[arg-type]
        )


@pytest.mark.parametrize("invalid_plan", (None, object(), "plan"))
def test_calculate_rejects_invalid_plan_type(invalid_plan: object) -> None:
    plan = _plan()

    with pytest.raises(RuleScoringError) as exc_info:
        RuleScoringCalculator.calculate(
            invalid_plan,  # type: ignore[arg-type]
            _planned_results(plan),
        )

    _assert_category(exc_info, RuleScoringErrorCategory.INVALID_SCORING_INPUT)


@pytest.mark.parametrize("invalid_results", (None, [], (), "results"))
def test_calculate_rejects_invalid_result_collection(invalid_results: object) -> None:
    with pytest.raises(RuleScoringError) as exc_info:
        RuleScoringCalculator.calculate(
            _plan(),
            invalid_results,  # type: ignore[arg-type]
        )

    _assert_category(exc_info, RuleScoringErrorCategory.INVALID_SCORING_INPUT)


def test_calculate_rejects_invalid_planned_result_element() -> None:
    with pytest.raises(RuleScoringError) as exc_info:
        RuleScoringCalculator.calculate(
            _plan((RuleId.R004,)),
            (cast(PlannedRuleResult, object()),),
        )

    _assert_category(exc_info, RuleScoringErrorCategory.INVALID_SCORING_INPUT)


@pytest.mark.parametrize("extra_result", (False, True))
def test_calculate_rejects_plan_result_count_mismatch_without_partial_score(
    extra_result: bool,
) -> None:
    plan = _plan((RuleId.R001, RuleId.R004))
    valid_results = _planned_results(plan, (RuleId.R001,))
    planned_results = valid_results + (valid_results[-1],) if extra_result else valid_results[:-1]
    sentinel = object()
    result: object = sentinel

    with pytest.raises(RuleScoringError) as exc_info:
        result = RuleScoringCalculator.calculate(plan, planned_results)

    _assert_category(exc_info, RuleScoringErrorCategory.SCORING_PLAN_RESULT_MISMATCH)
    assert result is sentinel


def test_calculate_rejects_rule_id_and_result_order_mismatch() -> None:
    plan = _plan((RuleId.R001, RuleId.R004))
    planned_results = _planned_results(plan)
    swapped_results = (planned_results[1], planned_results[0])

    with pytest.raises(RuleScoringError) as exc_info:
        RuleScoringCalculator.calculate(plan, swapped_results)

    _assert_category(exc_info, RuleScoringErrorCategory.SCORING_PLAN_RESULT_MISMATCH)


def test_calculate_rejects_rule_version_identity_mismatch() -> None:
    plan = _plan((RuleId.R004,))
    planned_result = _planned_results(plan)[0]
    mismatched_item = replace(
        planned_result.plan_item,
        rule_version_id=UUID("30000000-0000-4000-8000-000000000004"),
    )

    with pytest.raises(RuleScoringError) as exc_info:
        RuleScoringCalculator.calculate(
            plan,
            (replace(planned_result, plan_item=mismatched_item),),
        )

    _assert_category(exc_info, RuleScoringErrorCategory.SCORING_PLAN_RESULT_MISMATCH)


def test_calculate_rejects_execution_order_mismatch() -> None:
    valid_plan = _plan((RuleId.R004,))
    invalid_item = replace(valid_plan.items[0], execution_order=2)
    invalid_plan = replace(valid_plan, items=(invalid_item,))
    planned_results = (replace(_planned_results(valid_plan)[0], plan_item=invalid_item),)

    with pytest.raises(RuleScoringError) as exc_info:
        RuleScoringCalculator.calculate(invalid_plan, planned_results)

    _assert_category(exc_info, RuleScoringErrorCategory.SCORING_PLAN_RESULT_MISMATCH)


def test_calculate_rejects_raw_evaluation_result_rule_id_mismatch() -> None:
    plan = _plan((RuleId.R004,))
    planned_result = _planned_results(plan)[0]
    mismatched_result = replace(
        planned_result,
        evaluation_result=RuleEvaluationResult(
            rule_id=RuleId.R001,
            matched=False,
            facts=None,
        ),
    )

    with pytest.raises(RuleScoringError) as exc_info:
        RuleScoringCalculator.calculate(plan, (mismatched_result,))

    _assert_category(exc_info, RuleScoringErrorCategory.SCORING_PLAN_RESULT_MISMATCH)


def test_calculate_rejects_non_boolean_matched_value() -> None:
    plan = _plan((RuleId.R004,))
    invalid_result = PlannedRuleResult(
        plan_item=plan.items[0],
        evaluation_result=RuleEvaluationResult(
            rule_id=RuleId.R004,
            matched=cast(bool, 1),
            facts=object(),
        ),
    )

    with pytest.raises(RuleScoringError) as exc_info:
        RuleScoringCalculator.calculate(plan, (invalid_result,))

    _assert_category(exc_info, RuleScoringErrorCategory.INVALID_SCORING_INPUT)


def test_calculate_rejects_unsupported_rule() -> None:
    plan = _plan((RuleId.R004,))
    unsupported_item = replace(plan.items[0], rule_id=cast(RuleId, "R005"))
    unsupported_plan = replace(plan, items=(unsupported_item,))
    planned_results = (
        PlannedRuleResult(
            plan_item=unsupported_item,
            evaluation_result=RuleEvaluationResult(
                rule_id=cast(RuleId, "R005"),
                matched=False,
                facts=None,
            ),
        ),
    )

    with pytest.raises(RuleScoringError) as exc_info:
        RuleScoringCalculator.calculate(unsupported_plan, planned_results)

    _assert_category(exc_info, RuleScoringErrorCategory.UNSUPPORTED_SCORING_RULE)


@pytest.mark.parametrize("invalid_weight", (True, 0, 101, 11))
def test_calculate_rejects_invalid_or_noncanonical_rule_weight(invalid_weight: object) -> None:
    plan = _plan((RuleId.R004,))
    invalid_item = replace(plan.items[0], weight=invalid_weight)
    invalid_plan = replace(plan, items=(invalid_item,))
    planned_results = (replace(_planned_results(plan)[0], plan_item=invalid_item),)

    with pytest.raises(RuleScoringError) as exc_info:
        RuleScoringCalculator.calculate(invalid_plan, planned_results)

    _assert_category(exc_info, RuleScoringErrorCategory.INVALID_RULE_WEIGHT)


@pytest.mark.parametrize(
    ("rule_id", "invalid_weight"),
    (
        (RuleId.R001, 14),
        (RuleId.R002, 19),
        (RuleId.R003, 39),
        (RuleId.R004, 9),
    ),
)
def test_calculate_validates_each_rule_canonical_weight(
    rule_id: RuleId,
    invalid_weight: int,
) -> None:
    plan = _plan()
    invalid_index = tuple(item.rule_id for item in plan.items).index(rule_id)
    invalid_item = replace(plan.items[invalid_index], weight=invalid_weight)
    invalid_items = list(plan.items)
    invalid_items[invalid_index] = invalid_item
    invalid_plan = replace(plan, items=tuple(invalid_items))
    planned_results = list(_planned_results(plan))
    planned_results[invalid_index] = replace(
        planned_results[invalid_index],
        plan_item=invalid_item,
    )

    with pytest.raises(RuleScoringError) as exc_info:
        RuleScoringCalculator.calculate(invalid_plan, tuple(planned_results))

    _assert_category(exc_info, RuleScoringErrorCategory.INVALID_RULE_WEIGHT)


@pytest.mark.parametrize(
    "invalid_policy",
    (
        replace(scoring_module._SCORING_POLICY, version="scoring-policy-v2"),
        replace(
            scoring_module._SCORING_POLICY,
            rule_bindings=tuple(
                replace(binding, group_id=ScoringGroupId.AMOUNT)
                if binding.rule_id is RuleId.R002
                else binding
                for binding in scoring_module._SCORING_POLICY.rule_bindings
            ),
        ),
        replace(
            scoring_module._SCORING_POLICY,
            rule_bindings=tuple(
                replace(binding, canonical_weight=21) if binding.rule_id is RuleId.R002 else binding
                for binding in scoring_module._SCORING_POLICY.rule_bindings
            ),
        ),
        replace(
            scoring_module._SCORING_POLICY,
            group_caps=(
                (ScoringGroupId.AMOUNT, 15),
                (ScoringGroupId.SECURITY, 59),
            ),
        ),
        replace(scoring_module._SCORING_POLICY, final_score_cap=99),
        replace(
            scoring_module._SCORING_POLICY,
            risk_boundaries=tuple(
                replace(boundary, maximum_score=18)
                if boundary.risk_level is RiskLevel.LOW
                else boundary
                for boundary in scoring_module._SCORING_POLICY.risk_boundaries
            ),
        ),
        replace(
            scoring_module._SCORING_POLICY,
            rule_bindings=tuple(
                replace(binding, group_id=cast(ScoringGroupId, "amount"))
                if binding.rule_id is RuleId.R001
                else binding
                for binding in scoring_module._SCORING_POLICY.rule_bindings
            ),
        ),
        replace(
            scoring_module._SCORING_POLICY,
            risk_boundaries=tuple(
                replace(boundary, risk_level=cast(RiskLevel, "HIGH"))
                if boundary.risk_level is RiskLevel.HIGH
                else boundary
                for boundary in scoring_module._SCORING_POLICY.risk_boundaries
            ),
        ),
        replace(
            scoring_module._SCORING_POLICY,
            rule_bindings=tuple(
                replace(binding, rule_id=cast(RuleId, "R001"))
                if binding.rule_id is RuleId.R001
                else binding
                for binding in scoring_module._SCORING_POLICY.rule_bindings
            ),
        ),
        replace(
            scoring_module._SCORING_POLICY,
            group_caps=(
                (cast(ScoringGroupId, "amount"), 15),
                (ScoringGroupId.SECURITY, 60),
            ),
        ),
        replace(
            scoring_module._SCORING_POLICY,
            rule_bindings=tuple(
                replace(binding, canonical_weight=True)
                if binding.rule_id is RuleId.R001
                else binding
                for binding in scoring_module._SCORING_POLICY.rule_bindings
            ),
        ),
        replace(
            scoring_module._SCORING_POLICY,
            group_caps=(
                (ScoringGroupId.AMOUNT, 15),
                (ScoringGroupId.SECURITY, True),
            ),
        ),
        replace(scoring_module._SCORING_POLICY, final_score_cap=True),
        replace(
            scoring_module._SCORING_POLICY,
            risk_boundaries=tuple(
                replace(boundary, minimum_score=False)
                if boundary.risk_level is RiskLevel.LOW
                else boundary
                for boundary in scoring_module._SCORING_POLICY.risk_boundaries
            ),
        ),
        replace(
            scoring_module._SCORING_POLICY,
            rule_bindings=scoring_module._SCORING_POLICY.rule_bindings[:-1],
        ),
        replace(
            scoring_module._SCORING_POLICY,
            rule_bindings=scoring_module._SCORING_POLICY.rule_bindings
            + (scoring_module._SCORING_POLICY.rule_bindings[-1],),
        ),
        replace(
            scoring_module._SCORING_POLICY,
            rule_bindings=(
                scoring_module._SCORING_POLICY.rule_bindings[1],
                scoring_module._SCORING_POLICY.rule_bindings[0],
                *scoring_module._SCORING_POLICY.rule_bindings[2:],
            ),
        ),
        replace(
            scoring_module._SCORING_POLICY,
            group_caps=scoring_module._SCORING_POLICY.group_caps[:-1],
        ),
        replace(
            scoring_module._SCORING_POLICY,
            group_caps=scoring_module._SCORING_POLICY.group_caps
            + (scoring_module._SCORING_POLICY.group_caps[-1],),
        ),
        replace(
            scoring_module._SCORING_POLICY,
            group_caps=tuple(reversed(scoring_module._SCORING_POLICY.group_caps)),
        ),
        replace(
            scoring_module._SCORING_POLICY,
            risk_boundaries=scoring_module._SCORING_POLICY.risk_boundaries[:-1],
        ),
        replace(
            scoring_module._SCORING_POLICY,
            risk_boundaries=scoring_module._SCORING_POLICY.risk_boundaries
            + (scoring_module._SCORING_POLICY.risk_boundaries[-1],),
        ),
        replace(
            scoring_module._SCORING_POLICY,
            risk_boundaries=(
                scoring_module._SCORING_POLICY.risk_boundaries[1],
                scoring_module._SCORING_POLICY.risk_boundaries[0],
                *scoring_module._SCORING_POLICY.risk_boundaries[2:],
            ),
        ),
    ),
    ids=(
        "version",
        "r002-group",
        "r002-weight",
        "group-cap",
        "final-cap",
        "risk-boundary",
        "r001-group-raw-string",
        "high-risk-level-raw-string",
        "r001-rule-id-raw-string",
        "group-cap-id-raw-string",
        "binding-weight-bool",
        "group-cap-bool",
        "final-cap-bool",
        "risk-boundary-bool",
        "missing-binding",
        "extra-binding",
        "binding-order",
        "missing-group",
        "extra-group",
        "group-order",
        "missing-boundary",
        "extra-boundary",
        "boundary-order",
    ),
)
def test_calculate_rejects_invalid_policy_binding(
    monkeypatch: pytest.MonkeyPatch,
    invalid_policy: object,
) -> None:
    plan = _plan()
    planned_results = _planned_results(plan, tuple(item.rule_id for item in plan.items))
    sentinel = object()
    result: object = sentinel
    monkeypatch.setattr(scoring_module, "_SCORING_POLICY", invalid_policy)

    with pytest.raises(RuleScoringError) as exc_info:
        result = RuleScoringCalculator.calculate(plan, planned_results)

    _assert_category(exc_info, RuleScoringErrorCategory.INVALID_SCORING_POLICY)
    assert result is sentinel


@pytest.mark.parametrize(
    ("score", "expected_level"),
    (
        (19, RiskLevel.LOW),
        (20, RiskLevel.MEDIUM),
        (49, RiskLevel.MEDIUM),
        (50, RiskLevel.HIGH),
        (79, RiskLevel.HIGH),
        (80, RiskLevel.CRITICAL),
        (100, RiskLevel.CRITICAL),
    ),
)
def test_internal_risk_level_boundaries(score: int, expected_level: RiskLevel) -> None:
    assert (
        scoring_module._risk_level_for_score(
            score,
            scoring_module._SCORING_POLICY,
        )
        is expected_level
    )
