from dataclasses import FrozenInstanceError, replace
from datetime import UTC, datetime, timedelta
from typing import cast
from uuid import UUID

import pytest

from finguardops_ai.rules.v1 import (
    BehaviorEventType,
    PlannedRuleResult,
    R004ConditionDefinition,
    RuleEvaluationInput,
    RuleEvaluationResult,
    RuleEvaluator,
    RuleEvaluatorRegistry,
    RuleEvaluatorResult,
    RuleEvaluatorResultMismatchError,
    RuleExecutionOrchestrator,
    RuleExecutionPlan,
    RuleExecutionPlanItem,
    RuleExecutionPlanRunner,
    RuleExecutionPlanRunnerError,
    RuleExecutionPlanRunnerErrorCategory,
    RuleId,
    UnsupportedRuleIdError,
)

CUTOFF_AT = datetime(2026, 7, 23, 12, 0, tzinfo=UTC)


@pytest.fixture
def rule_input(transaction_factory) -> RuleEvaluationInput:
    return RuleEvaluationInput(transaction=transaction_factory())


class _StubOrchestrator:
    def __init__(self, outcome: object) -> None:
        self.outcome = outcome
        self.calls: list[tuple[tuple[RuleId, ...], RuleEvaluationInput]] = []

    def execute(
        self,
        rule_ids: tuple[RuleId, ...],
        rule_input: RuleEvaluationInput,
    ) -> object:
        self.calls.append((rule_ids, rule_input))
        if isinstance(self.outcome, BaseException):
            raise self.outcome
        return self.outcome


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
        rule_code=f"RULE_{rule_id}",
        rule_id=rule_id,
        version_number=1,
        reason_code=f"REASON_{rule_id}",
        weight=number * 10,
        condition_definition=_condition(),
        effective_from=CUTOFF_AT - timedelta(days=1),
        effective_to=CUTOFF_AT + timedelta(days=1),
        execution_order=execution_order,
    )


def _plan(rule_ids: tuple[RuleId, ...] = (RuleId.R004,)) -> RuleExecutionPlan:
    return RuleExecutionPlan(
        evaluation_cutoff_at=CUTOFF_AT,
        rule_set_version="rule-set-version",
        items=tuple(_item(rule_id, index) for index, rule_id in enumerate(rule_ids, start=1)),
    )


def _result(rule_id: RuleId) -> RuleEvaluationResult[None]:
    return RuleEvaluationResult(rule_id=rule_id, matched=False, facts=None)


def _recording_evaluator(rule_id: RuleId, calls: list[RuleId]) -> RuleEvaluator:
    def evaluator(_rule_input: RuleEvaluationInput) -> RuleEvaluatorResult:
        calls.append(rule_id)
        return _result(rule_id)

    return evaluator


def _runner(stub: _StubOrchestrator) -> RuleExecutionPlanRunner:
    return RuleExecutionPlanRunner(cast(RuleExecutionOrchestrator, stub))


def _assert_category(
    exc_info: pytest.ExceptionInfo[RuleExecutionPlanRunnerError],
    expected: RuleExecutionPlanRunnerErrorCategory,
) -> None:
    assert exc_info.value.category is expected


def test_runner_error_categories_are_the_eight_public_semantic_categories() -> None:
    assert set(RuleExecutionPlanRunnerErrorCategory) == {
        RuleExecutionPlanRunnerErrorCategory.INVALID_PLAN_RUNNER_INPUT,
        RuleExecutionPlanRunnerErrorCategory.INVALID_RULE_EXECUTION_PLAN,
        RuleExecutionPlanRunnerErrorCategory.EVALUATION_CUTOFF_MISMATCH,
        RuleExecutionPlanRunnerErrorCategory.UNSUPPORTED_RULE_CAPABILITY,
        RuleExecutionPlanRunnerErrorCategory.RULE_EVALUATOR_EXECUTION_FAILED,
        RuleExecutionPlanRunnerErrorCategory.INVALID_RULE_EXECUTION_RESULT,
        RuleExecutionPlanRunnerErrorCategory.RULE_EXECUTION_RESULT_COUNT_MISMATCH,
        RuleExecutionPlanRunnerErrorCategory.RULE_EVALUATOR_RESULT_MISMATCH,
    }


@pytest.mark.parametrize("invalid_input", (None, object(), "R004"))
def test_runner_rejects_invalid_plan_type_before_orchestrator(
    invalid_input: object,
    rule_input: RuleEvaluationInput,
) -> None:
    stub = _StubOrchestrator((_result(RuleId.R004),))

    with pytest.raises(RuleExecutionPlanRunnerError) as exc_info:
        _runner(stub).execute(invalid_input, rule_input)  # type: ignore[arg-type]

    _assert_category(
        exc_info,
        RuleExecutionPlanRunnerErrorCategory.INVALID_PLAN_RUNNER_INPUT,
    )
    assert stub.calls == []


def test_runner_rejects_invalid_rule_input_type_before_orchestrator() -> None:
    stub = _StubOrchestrator((_result(RuleId.R004),))

    with pytest.raises(RuleExecutionPlanRunnerError) as exc_info:
        _runner(stub).execute(_plan(), object())  # type: ignore[arg-type]

    _assert_category(
        exc_info,
        RuleExecutionPlanRunnerErrorCategory.INVALID_PLAN_RUNNER_INPUT,
    )
    assert stub.calls == []


@pytest.mark.parametrize(
    "items",
    (
        [],
        (),
        (object(),),
        (replace(_item(RuleId.R004, 1), execution_order=2),),
        (_item(RuleId.R001, 1), _item(RuleId.R004, 1)),
        (_item(RuleId.R001, 1), _item(RuleId.R001, 2)),
        (_item(RuleId.R004, 1), _item(RuleId.R001, 2)),
    ),
)
def test_runner_rejects_invalid_plan_structure_before_orchestrator(
    items: object,
    rule_input: RuleEvaluationInput,
) -> None:
    stub = _StubOrchestrator((_result(RuleId.R004),))
    plan = RuleExecutionPlan(CUTOFF_AT, "rule-set-version", items)  # type: ignore[arg-type]

    with pytest.raises(RuleExecutionPlanRunnerError) as exc_info:
        _runner(stub).execute(plan, rule_input)

    _assert_category(
        exc_info,
        RuleExecutionPlanRunnerErrorCategory.INVALID_RULE_EXECUTION_PLAN,
    )
    assert stub.calls == []


def test_runner_rejects_cutoff_mismatch_before_orchestrator(
    transaction_factory,
) -> None:
    rule_input = RuleEvaluationInput(
        transaction=transaction_factory(occurred_at=CUTOFF_AT + timedelta(microseconds=1))
    )
    stub = _StubOrchestrator((_result(RuleId.R004),))

    with pytest.raises(RuleExecutionPlanRunnerError) as exc_info:
        _runner(stub).execute(_plan(), rule_input)

    _assert_category(
        exc_info,
        RuleExecutionPlanRunnerErrorCategory.EVALUATION_CUTOFF_MISMATCH,
    )
    assert stub.calls == []


@pytest.mark.parametrize(
    "rule_ids",
    (
        (RuleId.R004,),
        (RuleId.R002, RuleId.R004),
        (RuleId.R001, RuleId.R003, RuleId.R004),
        (RuleId.R001, RuleId.R002, RuleId.R003, RuleId.R004),
    ),
)
def test_runner_accepts_every_supported_canonical_subsequence(
    rule_ids: tuple[RuleId, ...],
    rule_input: RuleEvaluationInput,
) -> None:
    stub = _StubOrchestrator(tuple(_result(rule_id) for rule_id in rule_ids))

    planned_results = _runner(stub).execute(_plan(rule_ids), rule_input)

    assert len(stub.calls) == 1
    passed_rule_ids, passed_rule_input = stub.calls[0]
    assert passed_rule_ids == rule_ids
    assert passed_rule_input is rule_input
    assert tuple(result.evaluation_result.rule_id for result in planned_results) == rule_ids


def test_runner_rejects_unknown_rule_id_before_orchestrator(
    rule_input: RuleEvaluationInput,
) -> None:
    unknown_item = replace(
        _item(RuleId.R004, 1),
        rule_id=cast(RuleId, "R005"),
    )
    plan = RuleExecutionPlan(CUTOFF_AT, "rule-set-version", (unknown_item,))
    stub = _StubOrchestrator((_result(RuleId.R004),))

    with pytest.raises(RuleExecutionPlanRunnerError) as exc_info:
        _runner(stub).execute(plan, rule_input)

    _assert_category(
        exc_info,
        RuleExecutionPlanRunnerErrorCategory.INVALID_RULE_EXECUTION_PLAN,
    )
    assert stub.calls == []


def test_runner_calls_orchestrator_once_in_plan_physical_order_and_combines_strictly(
    rule_input: RuleEvaluationInput,
) -> None:
    plan = _plan((RuleId.R001, RuleId.R003, RuleId.R004))
    raw_results = (_result(RuleId.R001), _result(RuleId.R003), _result(RuleId.R004))
    stub = _StubOrchestrator(raw_results)

    planned_results = _runner(stub).execute(plan, rule_input)

    assert len(stub.calls) == 1
    passed_rule_ids, passed_rule_input = stub.calls[0]
    assert passed_rule_ids == (RuleId.R001, RuleId.R003, RuleId.R004)
    assert passed_rule_input is rule_input
    assert isinstance(planned_results, tuple)
    assert tuple(result.plan_item for result in planned_results) == plan.items
    assert tuple(result.evaluation_result for result in planned_results) == raw_results
    assert all(
        result.plan_item is plan_item and result.evaluation_result is raw_result
        for result, plan_item, raw_result in zip(
            planned_results,
            plan.items,
            raw_results,
            strict=True,
        )
    )
    with pytest.raises(FrozenInstanceError):
        planned_results[0].evaluation_result = raw_results[0]


def test_planned_rule_result_uses_slots() -> None:
    planned_result = PlannedRuleResult(
        plan_item=_plan().items[0],
        evaluation_result=_result(RuleId.R004),
    )

    assert not hasattr(planned_result, "__dict__")
    with pytest.raises(AttributeError):
        object.__setattr__(planned_result, "unexpected", object())


def test_runner_preserves_one_planned_result_for_an_unmatched_rule(
    rule_input: RuleEvaluationInput,
) -> None:
    raw_result = _result(RuleId.R004)

    planned_results = _runner(_StubOrchestrator((raw_result,))).execute(_plan(), rule_input)

    assert planned_results == (
        PlannedRuleResult(plan_item=_plan().items[0], evaluation_result=raw_result),
    )


def test_runner_translates_unsupported_capability_once_and_preserves_cause(
    rule_input: RuleEvaluationInput,
) -> None:
    cause = UnsupportedRuleIdError(RuleId.R004)
    stub = _StubOrchestrator(cause)

    with pytest.raises(RuleExecutionPlanRunnerError) as exc_info:
        _runner(stub).execute(_plan(), rule_input)

    _assert_category(
        exc_info,
        RuleExecutionPlanRunnerErrorCategory.UNSUPPORTED_RULE_CAPABILITY,
    )
    assert exc_info.value.__cause__ is cause
    assert stub.calls == [((RuleId.R004,), rule_input)]


def test_runner_translates_actual_registry_capability_failure_without_running_evaluator(
    rule_input: RuleEvaluationInput,
) -> None:
    evaluator_calls: list[RuleId] = []
    registry = RuleEvaluatorRegistry(
        ((RuleId.R001, _recording_evaluator(RuleId.R001, evaluator_calls)),)
    )
    runner = RuleExecutionPlanRunner(RuleExecutionOrchestrator(registry))
    sentinel = object()
    planned_results: object = sentinel

    with pytest.raises(RuleExecutionPlanRunnerError) as exc_info:
        planned_results = runner.execute(_plan((RuleId.R001, RuleId.R004)), rule_input)

    _assert_category(
        exc_info,
        RuleExecutionPlanRunnerErrorCategory.UNSUPPORTED_RULE_CAPABILITY,
    )
    assert isinstance(exc_info.value.__cause__, UnsupportedRuleIdError)
    assert exc_info.value.__cause__.rule_id is RuleId.R004
    assert exc_info.value.__cause__.__cause__ is None
    assert evaluator_calls == []
    assert planned_results is sentinel


def test_runner_translates_evaluator_exception_once_without_retry(
    rule_input: RuleEvaluationInput,
) -> None:
    cause = RuntimeError("evaluator failed")
    stub = _StubOrchestrator(cause)

    with pytest.raises(RuleExecutionPlanRunnerError) as exc_info:
        _runner(stub).execute(_plan(), rule_input)

    _assert_category(
        exc_info,
        RuleExecutionPlanRunnerErrorCategory.RULE_EVALUATOR_EXECUTION_FAILED,
    )
    assert exc_info.value.__cause__ is cause
    assert stub.calls == [((RuleId.R004,), rule_input)]


def test_runner_stops_actual_orchestrator_after_middle_evaluator_failure(
    rule_input: RuleEvaluationInput,
) -> None:
    evaluator_calls: list[RuleId] = []
    cause = RuntimeError("R002 evaluator failed")

    def fail(_rule_input: RuleEvaluationInput) -> RuleEvaluatorResult:
        evaluator_calls.append(RuleId.R002)
        raise cause

    registry = RuleEvaluatorRegistry(
        (
            (RuleId.R001, _recording_evaluator(RuleId.R001, evaluator_calls)),
            (RuleId.R002, fail),
            (RuleId.R004, _recording_evaluator(RuleId.R004, evaluator_calls)),
        )
    )
    runner = RuleExecutionPlanRunner(RuleExecutionOrchestrator(registry))
    sentinel = object()
    planned_results: object = sentinel

    with pytest.raises(RuleExecutionPlanRunnerError) as exc_info:
        planned_results = runner.execute(
            _plan((RuleId.R001, RuleId.R002, RuleId.R004)),
            rule_input,
        )

    _assert_category(
        exc_info,
        RuleExecutionPlanRunnerErrorCategory.RULE_EVALUATOR_EXECUTION_FAILED,
    )
    assert exc_info.value.__cause__ is cause
    assert evaluator_calls == [RuleId.R001, RuleId.R002]
    assert evaluator_calls.count(RuleId.R001) == 1
    assert evaluator_calls.count(RuleId.R002) == 1
    assert evaluator_calls.count(RuleId.R004) == 0
    assert planned_results is sentinel


def test_runner_does_not_catch_base_exception(rule_input: RuleEvaluationInput) -> None:
    cause = KeyboardInterrupt()
    stub = _StubOrchestrator(cause)

    with pytest.raises(KeyboardInterrupt) as exc_info:
        _runner(stub).execute(_plan(), rule_input)

    assert exc_info.value is cause
    assert stub.calls == [((RuleId.R004,), rule_input)]


def test_runner_does_not_catch_system_exit(rule_input: RuleEvaluationInput) -> None:
    cause = SystemExit(2)
    stub = _StubOrchestrator(cause)

    with pytest.raises(SystemExit) as exc_info:
        _runner(stub).execute(_plan(), rule_input)

    assert exc_info.value is cause
    assert len(stub.calls) == 1


def test_runner_translates_orchestrator_result_mismatch_and_preserves_cause(
    rule_input: RuleEvaluationInput,
) -> None:
    cause = RuleEvaluatorResultMismatchError(RuleId.R004, RuleId.R001)
    stub = _StubOrchestrator(cause)

    with pytest.raises(RuleExecutionPlanRunnerError) as exc_info:
        _runner(stub).execute(_plan(), rule_input)

    _assert_category(
        exc_info,
        RuleExecutionPlanRunnerErrorCategory.RULE_EVALUATOR_RESULT_MISMATCH,
    )
    assert exc_info.value.__cause__ is cause


@pytest.mark.parametrize(
    ("raw_results", "expected_category"),
    (
        (
            [_result(RuleId.R004)],
            RuleExecutionPlanRunnerErrorCategory.INVALID_RULE_EXECUTION_RESULT,
        ),
        (
            (object(), _result(RuleId.R001)),
            RuleExecutionPlanRunnerErrorCategory.INVALID_RULE_EXECUTION_RESULT,
        ),
        (
            (),
            RuleExecutionPlanRunnerErrorCategory.RULE_EXECUTION_RESULT_COUNT_MISMATCH,
        ),
        (
            (_result(RuleId.R004), _result(RuleId.R001)),
            RuleExecutionPlanRunnerErrorCategory.RULE_EXECUTION_RESULT_COUNT_MISMATCH,
        ),
        (
            (_result(RuleId.R001),),
            RuleExecutionPlanRunnerErrorCategory.RULE_EVALUATOR_RESULT_MISMATCH,
        ),
    ),
)
def test_runner_validates_raw_results_in_deterministic_priority(
    raw_results: object,
    expected_category: RuleExecutionPlanRunnerErrorCategory,
    rule_input: RuleEvaluationInput,
) -> None:
    stub = _StubOrchestrator(raw_results)

    with pytest.raises(RuleExecutionPlanRunnerError) as exc_info:
        _runner(stub).execute(_plan(), rule_input)

    _assert_category(exc_info, expected_category)
    assert stub.calls == [((RuleId.R004,), rule_input)]


def test_runner_rejects_results_returned_in_a_different_order(
    rule_input: RuleEvaluationInput,
) -> None:
    plan = _plan((RuleId.R001, RuleId.R004))
    stub = _StubOrchestrator((_result(RuleId.R004), _result(RuleId.R001)))

    with pytest.raises(RuleExecutionPlanRunnerError) as exc_info:
        _runner(stub).execute(plan, rule_input)

    _assert_category(
        exc_info,
        RuleExecutionPlanRunnerErrorCategory.RULE_EVALUATOR_RESULT_MISMATCH,
    )
    assert stub.calls == [((RuleId.R001, RuleId.R004), rule_input)]


def test_runner_executes_each_actual_evaluator_once_in_plan_order(
    rule_input: RuleEvaluationInput,
) -> None:
    evaluator_calls: list[RuleId] = []
    registry = RuleEvaluatorRegistry(
        (
            (RuleId.R004, _recording_evaluator(RuleId.R004, evaluator_calls)),
            (RuleId.R003, _recording_evaluator(RuleId.R003, evaluator_calls)),
            (RuleId.R001, _recording_evaluator(RuleId.R001, evaluator_calls)),
        )
    )
    plan_rule_ids = (RuleId.R001, RuleId.R003, RuleId.R004)
    runner = RuleExecutionPlanRunner(RuleExecutionOrchestrator(registry))

    planned_results = runner.execute(_plan(plan_rule_ids), rule_input)

    assert evaluator_calls == list(plan_rule_ids)
    assert all(evaluator_calls.count(rule_id) == 1 for rule_id in plan_rule_ids)
    assert tuple(result.evaluation_result.rule_id for result in planned_results) == plan_rule_ids
