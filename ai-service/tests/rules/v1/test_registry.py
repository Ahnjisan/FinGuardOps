from dataclasses import FrozenInstanceError

import pytest

from finguardops_ai.rules.v1.models import RuleEvaluationInput, RuleId
from finguardops_ai.rules.v1.r001 import evaluate_r001
from finguardops_ai.rules.v1.r002 import evaluate_r002
from finguardops_ai.rules.v1.r003 import evaluate_r003
from finguardops_ai.rules.v1.r004 import evaluate_r004
from finguardops_ai.rules.v1.registry import (
    DuplicateRuleIdError,
    RuleEvaluatorRegistry,
    UnsupportedRuleIdError,
    create_default_rule_evaluator_registry,
)


def test_default_registry_has_official_rule_ids_in_deterministic_order() -> None:
    registry = create_default_rule_evaluator_registry()

    assert registry.supported_rule_ids == (
        RuleId.R001,
        RuleId.R002,
        RuleId.R003,
        RuleId.R004,
    )


@pytest.mark.parametrize(
    ("rule_id", "expected_evaluator"),
    (
        (RuleId.R001, evaluate_r001),
        (RuleId.R002, evaluate_r002),
        (RuleId.R003, evaluate_r003),
        (RuleId.R004, evaluate_r004),
    ),
)
def test_default_registry_maps_each_rule_id_to_its_evaluator(rule_id, expected_evaluator) -> None:
    registry = create_default_rule_evaluator_registry()

    assert registry.get_evaluator(rule_id) is expected_evaluator


def test_registry_accepts_exact_string_and_rule_id_lookup() -> None:
    registry = create_default_rule_evaluator_registry()

    assert registry.get_evaluator("R001") is evaluate_r001
    assert registry.get_evaluator(RuleId.R001) is evaluate_r001


@pytest.mark.parametrize("rule_id", ("r001", " R001", "R001 ", "R005"))
def test_registry_rejects_unsupported_rule_id_without_normalization(rule_id: str) -> None:
    registry = create_default_rule_evaluator_registry()

    with pytest.raises(UnsupportedRuleIdError) as exc_info:
        registry.get_evaluator(rule_id)

    assert exc_info.value.rule_id == rule_id


def test_registry_rejects_duplicate_rule_id_registration() -> None:
    registrations = (
        (RuleId.R001, evaluate_r001),
        (RuleId.R001, evaluate_r002),
    )

    with pytest.raises(DuplicateRuleIdError) as exc_info:
        RuleEvaluatorRegistry(registrations)

    assert exc_info.value.rule_id is RuleId.R001


def test_registry_state_is_immutable() -> None:
    registry = create_default_rule_evaluator_registry()

    with pytest.raises(FrozenInstanceError):
        registry._supported_rule_ids = ()
    with pytest.raises(TypeError):
        registry._evaluators[RuleId.R001] = evaluate_r002  # type: ignore[index]


def test_registry_snapshots_mutable_registration_input() -> None:
    registrations = [(RuleId.R001, evaluate_r001)]
    registry = RuleEvaluatorRegistry(registrations)

    registrations[0] = (RuleId.R002, evaluate_r002)
    registrations.append((RuleId.R003, evaluate_r003))

    assert registry.supported_rule_ids == (RuleId.R001,)
    assert registry.get_evaluator("R001") is evaluate_r001
    with pytest.raises(UnsupportedRuleIdError):
        registry.get_evaluator("R002")


@pytest.mark.parametrize(
    ("rule_id", "direct_evaluator"),
    (
        (RuleId.R001, evaluate_r001),
        (RuleId.R002, evaluate_r002),
        (RuleId.R003, evaluate_r003),
        (RuleId.R004, evaluate_r004),
    ),
)
def test_registry_evaluator_preserves_existing_evaluation_result(
    rule_id, direct_evaluator, transaction_factory
) -> None:
    registry = create_default_rule_evaluator_registry()
    rule_input = RuleEvaluationInput(transaction=transaction_factory())

    assert registry.get_evaluator(rule_id)(rule_input) == direct_evaluator(rule_input)
