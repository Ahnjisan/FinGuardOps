from collections.abc import Callable, Iterator, Mapping, Sequence

import pytest

from finguardops_ai.rules.v1 import (
    InvalidRuleExecutionPlanError,
    RuleEvaluatorResultMismatchError,
    RuleExecutionOrchestrator,
)
from finguardops_ai.rules.v1.models import (
    RuleEvaluationInput,
    RuleEvaluationResult,
    RuleId,
)
from finguardops_ai.rules.v1.registry import (
    RuleEvaluator,
    RuleEvaluatorRegistry,
    RuleEvaluatorResult,
    UnsupportedRuleIdError,
    create_default_rule_evaluator_registry,
)


class _RecordingRegistry:
    def __init__(
        self,
        evaluators: Mapping[str, RuleEvaluator],
        events: list[str],
        on_first_resolution: Callable[[], None] | None = None,
    ) -> None:
        self._evaluators = evaluators
        self._events = events
        self._on_first_resolution = on_first_resolution

    def get_evaluator(self, rule_id: str) -> RuleEvaluator:
        self._events.append(f"resolve:{rule_id}")
        if self._on_first_resolution is not None:
            on_first_resolution = self._on_first_resolution
            self._on_first_resolution = None
            on_first_resolution()
        return self._evaluators[rule_id]


class _SinglePassSequence(Sequence[str]):
    def __init__(self, values: Sequence[str]) -> None:
        self._values = tuple(values)
        self.iteration_count = 0

    def __getitem__(self, index):
        return self._values[index]

    def __len__(self) -> int:
        return len(self._values)

    def __iter__(self) -> Iterator[str]:
        self.iteration_count += 1
        if self.iteration_count > 1:
            raise AssertionError("Rule ID Sequence was traversed more than once")
        return iter(self._values)


class _EvaluatorFailure(RuntimeError):
    pass


@pytest.fixture
def rule_input(transaction_factory) -> RuleEvaluationInput:
    return RuleEvaluationInput(transaction=transaction_factory())


def _result(rule_id: RuleId) -> RuleEvaluatorResult:
    return RuleEvaluationResult(rule_id=rule_id, matched=False, facts=None)


def _recording_evaluator(rule_id: RuleId, events: list[str]) -> RuleEvaluator:
    def evaluator(_rule_input: RuleEvaluationInput) -> RuleEvaluatorResult:
        events.append(f"execute:{rule_id}")
        return _result(rule_id)

    return evaluator


def test_execute_returns_complete_tuple_in_caller_order(rule_input) -> None:
    events: list[str] = []
    registry = RuleEvaluatorRegistry(
        (
            (RuleId.R001, _recording_evaluator(RuleId.R001, events)),
            (RuleId.R004, _recording_evaluator(RuleId.R004, events)),
        )
    )
    orchestrator = RuleExecutionOrchestrator(registry)

    results = orchestrator.execute((RuleId.R004, RuleId.R001), rule_input)

    assert isinstance(results, tuple)
    assert len(results) == 2
    assert tuple(result.rule_id for result in results) == (RuleId.R004, RuleId.R001)
    assert events == ["execute:R004", "execute:R001"]


def test_execute_traverses_input_sequence_once(rule_input) -> None:
    rule_ids = _SinglePassSequence((RuleId.R004, RuleId.R001))
    orchestrator = RuleExecutionOrchestrator(create_default_rule_evaluator_registry())

    results = orchestrator.execute(rule_ids, rule_input)

    assert rule_ids.iteration_count == 1
    assert tuple(result.rule_id for result in results) == (RuleId.R004, RuleId.R001)


def test_execute_uses_snapshot_when_original_list_changes_during_resolution(rule_input) -> None:
    rule_ids: list[str] = [RuleId.R004, RuleId.R001]
    events: list[str] = []
    evaluators = {
        RuleId.R001: _recording_evaluator(RuleId.R001, events),
        RuleId.R004: _recording_evaluator(RuleId.R004, events),
    }

    def mutate_original_rule_ids() -> None:
        rule_ids[:] = [RuleId.R002]

    registry = _RecordingRegistry(evaluators, events, mutate_original_rule_ids)
    orchestrator = RuleExecutionOrchestrator(registry)

    results = orchestrator.execute(rule_ids, rule_input)

    assert rule_ids == [RuleId.R002]
    assert events == [
        "resolve:R004",
        "resolve:R001",
        "execute:R004",
        "execute:R001",
    ]
    assert tuple(result.rule_id for result in results) == (RuleId.R004, RuleId.R001)


@pytest.mark.parametrize("rule_ids", ("R001", b"R001"))
def test_execute_rejects_string_and_bytes_collections(rule_ids, rule_input) -> None:
    orchestrator = RuleExecutionOrchestrator(create_default_rule_evaluator_registry())

    with pytest.raises(InvalidRuleExecutionPlanError, match="non-string Sequence"):
        orchestrator.execute(rule_ids, rule_input)


def test_execute_rejects_set_as_non_sequence(rule_input) -> None:
    orchestrator = RuleExecutionOrchestrator(create_default_rule_evaluator_registry())

    with pytest.raises(InvalidRuleExecutionPlanError, match="must be a Sequence"):
        orchestrator.execute({"R001"}, rule_input)


@pytest.mark.parametrize(
    "rule_ids",
    (
        iter(("R001",)),
        (rule_id for rule_id in ("R001",)),
    ),
)
def test_execute_rejects_iterator_and_generator(rule_ids, rule_input) -> None:
    orchestrator = RuleExecutionOrchestrator(create_default_rule_evaluator_registry())

    with pytest.raises(InvalidRuleExecutionPlanError, match="must be a Sequence"):
        orchestrator.execute(rule_ids, rule_input)


def test_execute_rejects_non_string_element(rule_input) -> None:
    orchestrator = RuleExecutionOrchestrator(create_default_rule_evaluator_registry())

    with pytest.raises(InvalidRuleExecutionPlanError, match=r"rule_ids\[1\]"):
        orchestrator.execute(["R001", 1], rule_input)


def test_execute_rejects_empty_plan_without_running_evaluator(rule_input) -> None:
    events: list[str] = []
    registry = RuleEvaluatorRegistry(((RuleId.R001, _recording_evaluator(RuleId.R001, events)),))
    orchestrator = RuleExecutionOrchestrator(registry)

    with pytest.raises(InvalidRuleExecutionPlanError, match="must not be empty"):
        orchestrator.execute([], rule_input)

    assert events == []


@pytest.mark.parametrize(
    "rule_ids",
    (
        ("R001", "R001"),
        (RuleId.R001, "R001"),
    ),
)
def test_execute_rejects_exact_duplicate_rule_ids(rule_ids, rule_input) -> None:
    orchestrator = RuleExecutionOrchestrator(create_default_rule_evaluator_registry())

    with pytest.raises(InvalidRuleExecutionPlanError, match="duplicate Rule ID"):
        orchestrator.execute(rule_ids, rule_input)


@pytest.mark.parametrize("unsupported_rule_id", (" R001", "r001"))
def test_execute_does_not_normalize_rule_ids(unsupported_rule_id, rule_input) -> None:
    orchestrator = RuleExecutionOrchestrator(create_default_rule_evaluator_registry())

    with pytest.raises(UnsupportedRuleIdError) as exc_info:
        orchestrator.execute([unsupported_rule_id], rule_input)

    assert exc_info.value.rule_id == unsupported_rule_id


def test_execute_resolves_every_evaluator_before_first_execution(rule_input) -> None:
    events: list[str] = []
    evaluators = {
        RuleId.R001: _recording_evaluator(RuleId.R001, events),
        RuleId.R004: _recording_evaluator(RuleId.R004, events),
    }
    registry = _RecordingRegistry(evaluators, events)
    orchestrator = RuleExecutionOrchestrator(registry)

    orchestrator.execute((RuleId.R004, RuleId.R001), rule_input)

    assert events == [
        "resolve:R004",
        "resolve:R001",
        "execute:R004",
        "execute:R001",
    ]


def test_execute_runs_no_evaluator_when_later_rule_id_is_unsupported(rule_input) -> None:
    events: list[str] = []
    registry = RuleEvaluatorRegistry(((RuleId.R001, _recording_evaluator(RuleId.R001, events)),))
    orchestrator = RuleExecutionOrchestrator(registry)

    with pytest.raises(UnsupportedRuleIdError) as exc_info:
        orchestrator.execute((RuleId.R001, "R005"), rule_input)

    assert exc_info.value.rule_id == "R005"
    assert events == []


def test_execute_propagates_evaluator_failure_and_returns_no_partial_result(rule_input) -> None:
    events: list[str] = []
    failure = _EvaluatorFailure("evaluator failed")

    def fail(_rule_input: RuleEvaluationInput) -> RuleEvaluatorResult:
        events.append("execute:R002")
        raise failure

    registry = RuleEvaluatorRegistry(
        (
            (RuleId.R001, _recording_evaluator(RuleId.R001, events)),
            (RuleId.R002, fail),
            (RuleId.R004, _recording_evaluator(RuleId.R004, events)),
        )
    )
    orchestrator = RuleExecutionOrchestrator(registry)

    with pytest.raises(_EvaluatorFailure) as exc_info:
        orchestrator.execute((RuleId.R001, RuleId.R002, RuleId.R004), rule_input)

    assert exc_info.value is failure
    assert events == ["execute:R001", "execute:R002"]


def test_execute_rejects_result_rule_id_mismatch_and_stops_execution(rule_input) -> None:
    events: list[str] = []

    def mismatched(_rule_input: RuleEvaluationInput) -> RuleEvaluatorResult:
        events.append("execute:R001")
        return _result(RuleId.R002)

    registry = RuleEvaluatorRegistry(
        (
            (RuleId.R001, mismatched),
            (RuleId.R004, _recording_evaluator(RuleId.R004, events)),
        )
    )
    orchestrator = RuleExecutionOrchestrator(registry)

    with pytest.raises(RuleEvaluatorResultMismatchError) as exc_info:
        orchestrator.execute((RuleId.R001, RuleId.R004), rule_input)

    assert exc_info.value.requested_rule_id == RuleId.R001
    assert exc_info.value.returned_rule_id == RuleId.R002
    assert events == ["execute:R001"]
