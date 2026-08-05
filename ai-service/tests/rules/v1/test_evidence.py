from dataclasses import FrozenInstanceError, fields, replace
from datetime import UTC, datetime, timedelta, timezone
from decimal import Decimal
from typing import cast
from uuid import RFC_4122, UUID

import pytest

import finguardops_ai.rules.v1 as rule_v1
import finguardops_ai.rules.v1.evidence as evidence_module
from finguardops_ai.rules.v1 import (
    FraudRuleLifecycleStatus,
    PlannedRuleResult,
    R001Facts,
    R002Facts,
    R003Facts,
    R004Facts,
    RuleAnalysisResult,
    RuleEvaluationResult,
    RuleEvidenceError,
    RuleEvidenceErrorCategory,
    RuleEvidenceObservation,
    RuleEvidenceOutput,
    RuleEvidenceTransformer,
    RuleExecutionPlan,
    RuleExecutionPlanBuilder,
    RuleExecutionPlanItem,
    RuleId,
    RuleScoringCalculator,
    RuleScoringResult,
    RuleVersionSnapshot,
    RuleVersionStatus,
    ScoringGroupId,
    create_default_rule_evaluator_registry,
)

CUTOFF_AT = datetime(2026, 7, 23, 12, 0, tzinfo=UTC)
RULE_CODES = {
    RuleId.R001: "TRANSFER_ABSOLUTE_HIGH_AMOUNT",
    RuleId.R002: "RECENT_DEVICE_REGISTRATION_HIGH_AMOUNT",
    RuleId.R003: "RECENT_SECURITY_CHANGE_HIGH_AMOUNT",
    RuleId.R004: "RECENT_BENEFICIARY_TRANSFER",
}
WEIGHTS = {RuleId.R001: 15, RuleId.R002: 20, RuleId.R003: 40, RuleId.R004: 10}
CONDITIONS: dict[RuleId, dict[str, object]] = {
    RuleId.R001: {
        "transactionTypes": ["ACCOUNT_TRANSFER", "OPEN_BANKING_TRANSFER"],
        "currencyCode": "KRW",
        "amountThreshold": "10000000",
    },
    RuleId.R002: {
        "prerequisiteRuleCode": "TRANSFER_ABSOLUTE_HIGH_AMOUNT",
        "eventType": "DEVICE_REGISTERED",
        "windowSeconds": 86400,
        "matchPolicy": "SAME_CUSTOMER_AND_DEVICE",
        "selectionPolicy": "LATEST_OCCURRED_AT_THEN_EVENT_ID_ASC",
    },
    RuleId.R003: {
        "prerequisiteRuleCode": "TRANSFER_ABSOLUTE_HIGH_AMOUNT",
        "passwordEventType": "PASSWORD_CHANGED",
        "transferLimitEventType": "TRANSFER_LIMIT_CHANGED",
        "windowSeconds": 86400,
        "matchPolicy": "SAME_CUSTOMER_AND_SENDER_ACCOUNT",
        "sequencePolicy": "PASSWORD_CHANGED_AT_OR_BEFORE_TRANSFER_LIMIT_CHANGED",
        "selectionPolicy": (
            "LATEST_TRANSFER_LIMIT_THEN_EVENT_ID_ASC_LATEST_PASSWORD_THEN_EVENT_ID_ASC"
        ),
    },
    RuleId.R004: {
        "eventType": "BENEFICIARY_REGISTERED",
        "windowSeconds": 86400,
        "matchPolicy": "SAME_CUSTOMER_SENDER_ACCOUNT_AND_BENEFICIARY",
        "selectionPolicy": "LATEST_OCCURRED_AT_THEN_EVENT_ID_ASC",
    },
}


def _snapshot(rule_id: RuleId) -> RuleVersionSnapshot:
    number = int(rule_id.value[-1])
    return RuleVersionSnapshot(
        fraud_rule_id=UUID(f"10000000-0000-4000-8000-00000000000{number}"),
        rule_code=RULE_CODES[rule_id],
        lifecycle_status=FraudRuleLifecycleStatus.ACTIVE,
        rule_version_id=UUID(f"20000000-0000-4000-8000-00000000000{number}"),
        version_number=number,
        status=RuleVersionStatus.PUBLISHED,
        reason_code=RULE_CODES[rule_id],
        weight=WEIGHTS[rule_id],
        condition_definition=CONDITIONS[rule_id],
        effective_from=CUTOFF_AT - timedelta(days=1),
        effective_to=CUTOFF_AT + timedelta(days=1),
    )


def _plan(
    rule_ids: tuple[RuleId, ...] = (RuleId.R001, RuleId.R002, RuleId.R003, RuleId.R004),
) -> RuleExecutionPlan:
    builder = RuleExecutionPlanBuilder(create_default_rule_evaluator_registry())
    return builder.build(CUTOFF_AT, tuple(_snapshot(rule_id) for rule_id in rule_ids))


def _facts(rule_id: RuleId) -> object:
    if rule_id is RuleId.R001:
        return R001Facts(Decimal("12000000"), Decimal("10000000"))
    if rule_id is RuleId.R002:
        return R002Facts(
            Decimal("12000000"),
            Decimal("10000000"),
            UUID("30000000-0000-4000-8000-000000000002"),
            CUTOFF_AT - timedelta(seconds=120),
            120,
            86400,
        )
    if rule_id is RuleId.R003:
        return R003Facts(
            Decimal("12000000"),
            Decimal("10000000"),
            UUID("30000000-0000-4000-8000-000000000031"),
            CUTOFF_AT - timedelta(seconds=240),
            UUID("30000000-0000-4000-8000-000000000032"),
            CUTOFF_AT - timedelta(seconds=180),
            180,
            86400,
        )
    return R004Facts(
        Decimal("750000"),
        UUID("30000000-0000-4000-8000-000000000004"),
        CUTOFF_AT - timedelta(seconds=60),
        60,
        86400,
    )


def _planned_results(
    plan: RuleExecutionPlan,
    matched_rule_ids: tuple[RuleId, ...],
) -> tuple[PlannedRuleResult, ...]:
    return tuple(
        PlannedRuleResult(
            plan_item=item,
            evaluation_result=RuleEvaluationResult(
                rule_id=item.rule_id,
                matched=item.rule_id in matched_rule_ids,
                facts=_facts(item.rule_id) if item.rule_id in matched_rule_ids else None,
            ),
        )
        for item in plan.items
    )


def _inputs(
    matched_rule_ids: tuple[RuleId, ...] = (
        RuleId.R001,
        RuleId.R002,
        RuleId.R003,
        RuleId.R004,
    ),
) -> tuple[RuleExecutionPlan, tuple[PlannedRuleResult, ...], RuleScoringResult]:
    plan = _plan()
    planned_results = _planned_results(plan, matched_rule_ids)
    scoring_result = RuleScoringCalculator.calculate(plan, planned_results)
    return plan, planned_results, scoring_result


def _assert_category(
    exc_info: pytest.ExceptionInfo[RuleEvidenceError],
    expected: RuleEvidenceErrorCategory,
) -> None:
    assert exc_info.value.category is expected


def _replace_plan_item(
    plan: RuleExecutionPlan,
    planned_results: tuple[PlannedRuleResult, ...],
    index: int,
    item: RuleExecutionPlanItem,
) -> tuple[RuleExecutionPlan, tuple[PlannedRuleResult, ...]]:
    items = list(plan.items)
    items[index] = item
    results = list(planned_results)
    results[index] = replace(results[index], plan_item=item)
    return replace(plan, items=tuple(items)), tuple(results)


def test_evidence_error_categories_are_the_seven_public_semantic_categories() -> None:
    assert set(RuleEvidenceErrorCategory) == {
        RuleEvidenceErrorCategory.INVALID_RULE_EVIDENCE_INPUT,
        RuleEvidenceErrorCategory.RULE_EVIDENCE_PLAN_RESULT_MISMATCH,
        RuleEvidenceErrorCategory.RULE_EVIDENCE_SCORING_MISMATCH,
        RuleEvidenceErrorCategory.UNSUPPORTED_RULE_EVIDENCE,
        RuleEvidenceErrorCategory.INVALID_RULE_EVIDENCE_METADATA,
        RuleEvidenceErrorCategory.INVALID_RULE_EVIDENCE_FACTS,
        RuleEvidenceErrorCategory.INVALID_RULE_EVIDENCE_TIME,
    }


def test_transformer_is_only_entrypoint_and_forbidden_names_are_not_public() -> None:
    assert callable(RuleEvidenceTransformer.transform)
    assert not hasattr(evidence_module, "transform")
    for forbidden_name in ("DetectionResult", "DetectionEvidence", "RuleDetectionResult"):
        assert forbidden_name not in rule_v1.__all__
        assert not hasattr(evidence_module, forbidden_name)


def test_transform_all_rules_creates_exact_observations_and_evidence_times() -> None:
    plan, planned_results, scoring_result = _inputs()

    result = RuleEvidenceTransformer.transform(plan, planned_results, scoring_result)

    assert isinstance(result, RuleAnalysisResult)
    assert result.evaluation_cutoff_at == CUTOFF_AT
    assert result.rule_set_version == plan.rule_set_version
    assert result.scoring_result is scoring_result
    assert tuple(item.rule_id for item in result.evidence) == tuple(RuleId)
    assert tuple(item.rule_version for item in result.evidence) == ("1", "2", "3", "4")
    assert tuple(item.rule_code for item in result.evidence) == tuple(RULE_CODES.values())
    assert tuple(item.reason_code for item in result.evidence) == tuple(RULE_CODES.values())
    assert tuple(item.execution_order for item in result.evidence) == (1, 2, 3, 4)
    assert tuple(item.score_contribution for item in result.evidence) == (15, 20, 40, 10)
    assert tuple(item.evidence_occurred_at for item in result.evidence) == (
        CUTOFF_AT,
        CUTOFF_AT - timedelta(seconds=120),
        CUTOFF_AT - timedelta(seconds=180),
        CUTOFF_AT - timedelta(seconds=60),
    )

    expected_fields = (
        ("observed_amount", "amount_threshold"),
        (
            "observed_amount",
            "amount_threshold",
            "event_id",
            "device_registered_at",
            "elapsed_seconds",
            "window_seconds",
        ),
        (
            "observed_amount",
            "amount_threshold",
            "password_changed_event_id",
            "password_changed_at",
            "transfer_limit_changed_event_id",
            "transfer_limit_changed_at",
            "elapsed_seconds",
            "window_seconds",
        ),
        (
            "observed_amount",
            "event_id",
            "beneficiary_registered_at",
            "elapsed_seconds",
            "window_seconds",
        ),
    )
    for evidence, allowed_fields in zip(result.evidence, expected_fields, strict=True):
        assert isinstance(evidence, RuleEvidenceOutput)
        assert isinstance(evidence.observation_summary, RuleEvidenceObservation)
        assert tuple(field.name for field in fields(evidence.observation_summary)) == allowed_fields

    r004_observation = result.evidence[3].observation_summary
    assert r004_observation.observed_amount == Decimal("750000")  # type: ignore[attr-defined]
    assert not hasattr(r004_observation, "amount_threshold")


def test_observation_field_types_match_contract() -> None:
    result = RuleEvidenceTransformer.transform(*_inputs())
    r001, r002, r003, r004 = tuple(item.observation_summary for item in result.evidence)

    assert type(r001.observed_amount) is Decimal  # type: ignore[attr-defined]
    assert type(r001.amount_threshold) is Decimal  # type: ignore[attr-defined]
    assert type(r002.event_id) is UUID  # type: ignore[attr-defined]
    assert type(r002.device_registered_at) is datetime  # type: ignore[attr-defined]
    assert type(r002.elapsed_seconds) is int  # type: ignore[attr-defined]
    assert type(r002.window_seconds) is int  # type: ignore[attr-defined]
    assert type(r003.password_changed_event_id) is UUID  # type: ignore[attr-defined]
    assert type(r003.transfer_limit_changed_event_id) is UUID  # type: ignore[attr-defined]
    assert type(r003.password_changed_at) is datetime  # type: ignore[attr-defined]
    assert type(r003.transfer_limit_changed_at) is datetime  # type: ignore[attr-defined]
    assert type(r004.observed_amount) is Decimal  # type: ignore[attr-defined]


def test_unmatched_rules_are_excluded_without_renumbering_execution_order() -> None:
    plan, planned_results, scoring_result = _inputs((RuleId.R001, RuleId.R003))

    result = RuleEvidenceTransformer.transform(plan, planned_results, scoring_result)

    assert tuple(item.rule_id for item in result.evidence) == (RuleId.R001, RuleId.R003)
    assert tuple(item.execution_order for item in result.evidence) == (1, 3)


def test_evidence_and_nested_values_are_immutable() -> None:
    result = RuleEvidenceTransformer.transform(*_inputs())

    assert type(result.evidence) is tuple
    with pytest.raises(FrozenInstanceError):
        result.evaluation_cutoff_at = CUTOFF_AT + timedelta(seconds=1)
    with pytest.raises(FrozenInstanceError):
        result.evidence[0].execution_order = 99
    with pytest.raises(FrozenInstanceError):
        result.evidence[0].observation_summary.observed_amount = Decimal("1")  # type: ignore[attr-defined]
    with pytest.raises(TypeError):
        result.evidence[0] = result.evidence[0]


def test_group_cap_does_not_reduce_individual_evidence_contributions() -> None:
    result = RuleEvidenceTransformer.transform(*_inputs())

    assert sum(item.score_contribution for item in result.evidence) == 85
    assert result.scoring_result.risk_score == 75
    assert result.scoring_result.group_summaries[1].reduction == 10


@pytest.mark.parametrize("invalid_value", (None, object(), "invalid"))
def test_transform_rejects_invalid_top_level_input(invalid_value: object) -> None:
    plan, planned_results, scoring_result = _inputs()

    with pytest.raises(RuleEvidenceError) as plan_error:
        RuleEvidenceTransformer.transform(
            invalid_value,  # type: ignore[arg-type]
            planned_results,
            scoring_result,
        )
    _assert_category(plan_error, RuleEvidenceErrorCategory.INVALID_RULE_EVIDENCE_INPUT)

    with pytest.raises(RuleEvidenceError) as results_error:
        RuleEvidenceTransformer.transform(
            plan,
            cast(tuple[PlannedRuleResult, ...], [*planned_results]),
            scoring_result,
        )
    _assert_category(results_error, RuleEvidenceErrorCategory.INVALID_RULE_EVIDENCE_INPUT)


def test_transform_rejects_plan_result_count_and_index_identity_mismatch() -> None:
    plan, planned_results, scoring_result = _inputs()

    with pytest.raises(RuleEvidenceError) as count_error:
        RuleEvidenceTransformer.transform(plan, planned_results[:-1], scoring_result)
    _assert_category(
        count_error,
        RuleEvidenceErrorCategory.RULE_EVIDENCE_PLAN_RESULT_MISMATCH,
    )

    wrong_item = replace(plan.items[0], version_number=99)
    wrong_results = (replace(planned_results[0], plan_item=wrong_item), *planned_results[1:])
    with pytest.raises(RuleEvidenceError) as identity_error:
        RuleEvidenceTransformer.transform(plan, wrong_results, scoring_result)
    _assert_category(
        identity_error,
        RuleEvidenceErrorCategory.RULE_EVIDENCE_PLAN_RESULT_MISMATCH,
    )


def test_transform_rejects_rule_id_execution_order_and_duplicate_mismatch() -> None:
    plan, planned_results, scoring_result = _inputs()
    wrong_evaluation = RuleEvaluationResult(
        rule_id=RuleId.R002,
        matched=True,
        facts=_facts(RuleId.R002),
    )
    wrong_results = (
        replace(planned_results[0], evaluation_result=wrong_evaluation),
        *planned_results[1:],
    )
    with pytest.raises(RuleEvidenceError) as rule_id_error:
        RuleEvidenceTransformer.transform(plan, wrong_results, scoring_result)
    _assert_category(
        rule_id_error,
        RuleEvidenceErrorCategory.RULE_EVIDENCE_PLAN_RESULT_MISMATCH,
    )

    wrong_order_item = replace(plan.items[2], execution_order=4)
    wrong_plan, wrong_results = _replace_plan_item(plan, planned_results, 2, wrong_order_item)
    with pytest.raises(RuleEvidenceError) as order_error:
        RuleEvidenceTransformer.transform(wrong_plan, wrong_results, scoring_result)
    _assert_category(
        order_error,
        RuleEvidenceErrorCategory.RULE_EVIDENCE_PLAN_RESULT_MISMATCH,
    )

    duplicate_item = replace(
        plan.items[1],
        rule_id=RuleId.R001,
        rule_code=RULE_CODES[RuleId.R001],
        reason_code=RULE_CODES[RuleId.R001],
    )
    duplicate_plan, duplicate_results = _replace_plan_item(plan, planned_results, 1, duplicate_item)
    with pytest.raises(RuleEvidenceError) as duplicate_error:
        RuleEvidenceTransformer.transform(duplicate_plan, duplicate_results, scoring_result)
    _assert_category(
        duplicate_error,
        RuleEvidenceErrorCategory.INVALID_RULE_EVIDENCE_METADATA,
    )


def test_transform_rejects_unsupported_rule_id() -> None:
    plan, planned_results, scoring_result = _inputs()
    unsupported_item = replace(plan.items[0], rule_id=cast(RuleId, "R005"))
    unsupported_plan, unsupported_results = _replace_plan_item(
        plan, planned_results, 0, unsupported_item
    )

    with pytest.raises(RuleEvidenceError) as exc_info:
        RuleEvidenceTransformer.transform(unsupported_plan, unsupported_results, scoring_result)

    _assert_category(exc_info, RuleEvidenceErrorCategory.UNSUPPORTED_RULE_EVIDENCE)


@pytest.mark.parametrize(
    "scoring_mutator",
    (
        lambda scoring: replace(scoring, risk_score=scoring.risk_score + 1),
        lambda scoring: replace(
            scoring,
            rule_contributions=(
                replace(scoring.rule_contributions[0], matched=False),
                *scoring.rule_contributions[1:],
            ),
        ),
        lambda scoring: replace(
            scoring,
            rule_contributions=(
                replace(scoring.rule_contributions[0], original_contribution=14),
                *scoring.rule_contributions[1:],
            ),
        ),
        lambda scoring: replace(scoring, risk_level=cast(rule_v1.RiskLevel, "HIGH")),
        lambda scoring: replace(
            scoring,
            group_summaries=(
                replace(
                    scoring.group_summaries[0],
                    group_id=cast(ScoringGroupId, "amount"),
                ),
                scoring.group_summaries[1],
            ),
        ),
    ),
)
def test_transform_rejects_noncanonical_scoring_result(scoring_mutator) -> None:
    plan, planned_results, scoring_result = _inputs()

    with pytest.raises(RuleEvidenceError) as exc_info:
        RuleEvidenceTransformer.transform(
            plan,
            planned_results,
            scoring_mutator(scoring_result),
        )

    _assert_category(exc_info, RuleEvidenceErrorCategory.RULE_EVIDENCE_SCORING_MISMATCH)


def test_transform_rejects_scoring_count_identity_and_order_mismatch() -> None:
    plan, planned_results, scoring_result = _inputs()
    with pytest.raises(RuleEvidenceError) as count_error:
        RuleEvidenceTransformer.transform(
            plan,
            planned_results,
            replace(
                scoring_result,
                rule_contributions=scoring_result.rule_contributions[:-1],
            ),
        )
    _assert_category(
        count_error,
        RuleEvidenceErrorCategory.RULE_EVIDENCE_SCORING_MISMATCH,
    )

    wrong_identity = replace(scoring_result.rule_contributions[0], rule_id=RuleId.R002)
    with pytest.raises(RuleEvidenceError) as identity_error:
        RuleEvidenceTransformer.transform(
            plan,
            planned_results,
            replace(
                scoring_result,
                rule_contributions=(wrong_identity, *scoring_result.rule_contributions[1:]),
            ),
        )
    _assert_category(
        identity_error,
        RuleEvidenceErrorCategory.RULE_EVIDENCE_SCORING_MISMATCH,
    )

    wrong_order = replace(scoring_result.rule_contributions[0], execution_order=2)
    with pytest.raises(RuleEvidenceError) as order_error:
        RuleEvidenceTransformer.transform(
            plan,
            planned_results,
            replace(
                scoring_result,
                rule_contributions=(wrong_order, *scoring_result.rule_contributions[1:]),
            ),
        )
    _assert_category(
        order_error,
        RuleEvidenceErrorCategory.RULE_EVIDENCE_SCORING_MISMATCH,
    )


@pytest.mark.parametrize(
    "item_mutator",
    (
        lambda item: replace(item, rule_code="WRONG_RULE_CODE"),
        lambda item: replace(item, reason_code="WRONG_REASON_CODE"),
        lambda item: replace(item, version_number=0),
        lambda item: replace(
            item,
            rule_version_id=UUID("20000000-0000-1000-8000-000000000001"),
        ),
    ),
)
def test_transform_rejects_invalid_rule_version_metadata(item_mutator) -> None:
    plan, planned_results, _ = _inputs()
    invalid_item = item_mutator(plan.items[0])
    invalid_plan, invalid_results = _replace_plan_item(plan, planned_results, 0, invalid_item)
    invalid_scoring = RuleScoringCalculator.calculate(invalid_plan, invalid_results)

    with pytest.raises(RuleEvidenceError) as exc_info:
        RuleEvidenceTransformer.transform(invalid_plan, invalid_results, invalid_scoring)

    _assert_category(exc_info, RuleEvidenceErrorCategory.INVALID_RULE_EVIDENCE_METADATA)


def test_transform_rejects_noncanonical_rule_set_version() -> None:
    plan, planned_results, scoring_result = _inputs()

    with pytest.raises(RuleEvidenceError) as exc_info:
        RuleEvidenceTransformer.transform(
            replace(plan, rule_set_version="0" * 64),
            planned_results,
            scoring_result,
        )

    _assert_category(exc_info, RuleEvidenceErrorCategory.INVALID_RULE_EVIDENCE_METADATA)


def test_transform_maps_invalid_plan_weight_to_metadata_error() -> None:
    plan, planned_results, scoring_result = _inputs()
    invalid_item = replace(plan.items[0], weight=14)
    invalid_plan, invalid_results = _replace_plan_item(
        plan,
        planned_results,
        0,
        invalid_item,
    )

    with pytest.raises(RuleEvidenceError) as exc_info:
        RuleEvidenceTransformer.transform(invalid_plan, invalid_results, scoring_result)

    _assert_category(exc_info, RuleEvidenceErrorCategory.INVALID_RULE_EVIDENCE_METADATA)


def test_transform_rejects_wrong_facts_type_and_unmatched_facts() -> None:
    plan, planned_results, _ = _inputs()
    wrong_facts_result = RuleEvaluationResult(
        rule_id=RuleId.R001,
        matched=True,
        facts=_facts(RuleId.R002),
    )
    wrong_facts_results = (
        replace(planned_results[0], evaluation_result=wrong_facts_result),
        *planned_results[1:],
    )
    wrong_facts_scoring = RuleScoringCalculator.calculate(plan, wrong_facts_results)
    with pytest.raises(RuleEvidenceError) as wrong_type_error:
        RuleEvidenceTransformer.transform(plan, wrong_facts_results, wrong_facts_scoring)
    _assert_category(
        wrong_type_error,
        RuleEvidenceErrorCategory.INVALID_RULE_EVIDENCE_FACTS,
    )

    unmatched_result = RuleEvaluationResult(
        rule_id=RuleId.R001,
        matched=True,
        facts=_facts(RuleId.R001),
    )
    object.__setattr__(unmatched_result, "matched", False)
    unmatched_results = (
        replace(planned_results[0], evaluation_result=unmatched_result),
        *planned_results[1:],
    )
    unmatched_scoring = RuleScoringCalculator.calculate(plan, unmatched_results)
    with pytest.raises(RuleEvidenceError) as unmatched_error:
        RuleEvidenceTransformer.transform(plan, unmatched_results, unmatched_scoring)
    _assert_category(unmatched_error, RuleEvidenceErrorCategory.INVALID_RULE_EVIDENCE_FACTS)


def test_transform_rejects_invalid_behavior_uuid() -> None:
    plan, planned_results, _ = _inputs()
    r002_facts = cast(R002Facts, planned_results[1].evaluation_result.facts)
    invalid_facts = replace(
        r002_facts,
        event_id=UUID("30000000-0000-1000-8000-000000000002"),
    )
    invalid_result = RuleEvaluationResult(RuleId.R002, True, invalid_facts)
    invalid_results = list(planned_results)
    invalid_results[1] = replace(invalid_results[1], evaluation_result=invalid_result)
    invalid_results_tuple = tuple(invalid_results)
    invalid_scoring = RuleScoringCalculator.calculate(plan, invalid_results_tuple)

    with pytest.raises(RuleEvidenceError) as exc_info:
        RuleEvidenceTransformer.transform(plan, invalid_results_tuple, invalid_scoring)

    _assert_category(exc_info, RuleEvidenceErrorCategory.INVALID_RULE_EVIDENCE_FACTS)


def test_transform_rejects_non_rfc_4122_behavior_uuid_variant() -> None:
    plan, planned_results, _ = _inputs()
    r002_facts = cast(R002Facts, planned_results[1].evaluation_result.facts)
    non_rfc_variant_event_id = UUID("30000000-0000-4000-0000-000000000002")
    assert ((non_rfc_variant_event_id.int >> 76) & 0xF) == 4
    assert non_rfc_variant_event_id.version is None
    assert non_rfc_variant_event_id.variant != RFC_4122
    invalid_facts = replace(r002_facts, event_id=non_rfc_variant_event_id)
    invalid_result = RuleEvaluationResult(RuleId.R002, True, invalid_facts)
    invalid_results = list(planned_results)
    invalid_results[1] = replace(invalid_results[1], evaluation_result=invalid_result)
    invalid_results_tuple = tuple(invalid_results)
    invalid_scoring = RuleScoringCalculator.calculate(plan, invalid_results_tuple)

    with pytest.raises(RuleEvidenceError) as exc_info:
        RuleEvidenceTransformer.transform(plan, invalid_results_tuple, invalid_scoring)

    _assert_category(exc_info, RuleEvidenceErrorCategory.INVALID_RULE_EVIDENCE_FACTS)


@pytest.mark.parametrize(
    ("event_at", "elapsed_seconds"),
    (
        pytest.param(CUTOFF_AT, 0, id="cutoff"),
        pytest.param(CUTOFF_AT - timedelta(seconds=86400), 86400, id="window-start"),
    ),
)
def test_transform_accepts_inclusive_behavior_window_boundaries(
    event_at: datetime,
    elapsed_seconds: int,
) -> None:
    plan, planned_results, _ = _inputs()
    r002_facts = cast(R002Facts, planned_results[1].evaluation_result.facts)
    boundary_facts = replace(
        r002_facts,
        device_registered_at=event_at,
        elapsed_seconds=elapsed_seconds,
    )
    boundary_result = RuleEvaluationResult(RuleId.R002, True, boundary_facts)
    boundary_results = list(planned_results)
    boundary_results[1] = replace(
        boundary_results[1],
        evaluation_result=boundary_result,
    )
    boundary_results_tuple = tuple(boundary_results)
    boundary_scoring = RuleScoringCalculator.calculate(plan, boundary_results_tuple)

    result = RuleEvidenceTransformer.transform(
        plan,
        boundary_results_tuple,
        boundary_scoring,
    )

    evidence = result.evidence[1]
    observation = evidence.observation_summary
    assert evidence.rule_id is RuleId.R002
    assert evidence.evidence_occurred_at == event_at
    assert observation.device_registered_at == event_at  # type: ignore[attr-defined]
    assert observation.elapsed_seconds == elapsed_seconds  # type: ignore[attr-defined]
    assert observation.window_seconds == boundary_facts.window_seconds  # type: ignore[attr-defined]


@pytest.mark.parametrize(
    "facts_mutator",
    (
        lambda facts: replace(facts, device_registered_at=CUTOFF_AT + timedelta(seconds=1)),
        lambda facts: replace(
            facts,
            device_registered_at=datetime(2026, 7, 23, 12, 0),
        ),
        lambda facts: replace(facts, elapsed_seconds=119),
        lambda facts: replace(facts, window_seconds=0),
        lambda facts: replace(facts, window_seconds=86399),
    ),
)
def test_transform_rejects_invalid_behavior_time(facts_mutator) -> None:
    plan, planned_results, _ = _inputs()
    r002_facts = cast(R002Facts, planned_results[1].evaluation_result.facts)
    invalid_result = RuleEvaluationResult(RuleId.R002, True, facts_mutator(r002_facts))
    invalid_results = list(planned_results)
    invalid_results[1] = replace(invalid_results[1], evaluation_result=invalid_result)
    invalid_results_tuple = tuple(invalid_results)
    invalid_scoring = RuleScoringCalculator.calculate(plan, invalid_results_tuple)

    with pytest.raises(RuleEvidenceError) as exc_info:
        RuleEvidenceTransformer.transform(plan, invalid_results_tuple, invalid_scoring)

    _assert_category(exc_info, RuleEvidenceErrorCategory.INVALID_RULE_EVIDENCE_TIME)


def test_transform_rejects_non_utc_evaluation_cutoff() -> None:
    plan, planned_results, scoring_result = _inputs()
    invalid_plan = replace(
        plan,
        evaluation_cutoff_at=CUTOFF_AT.astimezone(timezone(timedelta(hours=9))),
    )

    with pytest.raises(RuleEvidenceError) as exc_info:
        RuleEvidenceTransformer.transform(invalid_plan, planned_results, scoring_result)

    _assert_category(exc_info, RuleEvidenceErrorCategory.INVALID_RULE_EVIDENCE_TIME)


@pytest.mark.parametrize(
    "facts_mutator",
    (
        lambda facts: replace(
            facts,
            password_changed_at=facts.transfer_limit_changed_at + timedelta(seconds=1),
        ),
        lambda facts: replace(
            facts,
            transfer_limit_changed_at=CUTOFF_AT + timedelta(seconds=1),
            elapsed_seconds=0,
        ),
        lambda facts: replace(facts, elapsed_seconds=facts.elapsed_seconds + 1),
    ),
)
def test_transform_rejects_r003_time_order_and_elapsed_violations(facts_mutator) -> None:
    plan, planned_results, _ = _inputs()
    r003_facts = cast(R003Facts, planned_results[2].evaluation_result.facts)
    invalid_result = RuleEvaluationResult(RuleId.R003, True, facts_mutator(r003_facts))
    invalid_results = list(planned_results)
    invalid_results[2] = replace(invalid_results[2], evaluation_result=invalid_result)
    invalid_results_tuple = tuple(invalid_results)
    invalid_scoring = RuleScoringCalculator.calculate(plan, invalid_results_tuple)

    with pytest.raises(RuleEvidenceError) as exc_info:
        RuleEvidenceTransformer.transform(plan, invalid_results_tuple, invalid_scoring)

    _assert_category(exc_info, RuleEvidenceErrorCategory.INVALID_RULE_EVIDENCE_TIME)
