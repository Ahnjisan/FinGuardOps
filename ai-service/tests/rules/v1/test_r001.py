from dataclasses import FrozenInstanceError
from datetime import datetime
from decimal import Decimal

import pytest

from finguardops_ai.rules.v1.models import (
    BehaviorEventType,
    R001Facts,
    RuleEvaluationInput,
    RuleId,
    TransactionType,
)
from finguardops_ai.rules.v1.r001 import evaluate_r001


def test_r001_matches_at_the_amount_threshold(transaction_factory) -> None:
    rule_input = RuleEvaluationInput(transaction=transaction_factory(amount=Decimal("10000000")))

    result = evaluate_r001(rule_input)

    assert result.rule_id is RuleId.R001
    assert result.matched is True
    assert result.facts == R001Facts(
        observed_amount=Decimal("10000000"),
        amount_threshold=Decimal("10000000"),
    )


def test_r001_does_not_match_immediately_below_threshold(transaction_factory) -> None:
    rule_input = RuleEvaluationInput(transaction=transaction_factory(amount=Decimal("9999999")))

    result = evaluate_r001(rule_input)

    assert result.matched is False
    assert result.facts is None


def test_r001_matches_immediately_above_threshold(transaction_factory) -> None:
    rule_input = RuleEvaluationInput(transaction=transaction_factory(amount=Decimal("10000001")))

    assert evaluate_r001(rule_input).matched is True


def test_r001_requires_supported_transaction_type_and_krw(transaction_factory) -> None:
    withdrawal = RuleEvaluationInput(
        transaction=transaction_factory(
            transaction_type=TransactionType.ATM_WITHDRAWAL,
            recipient_account_ref=None,
        )
    )
    usd_transfer = RuleEvaluationInput(transaction=transaction_factory(currency_code="USD"))

    assert evaluate_r001(withdrawal).matched is False
    assert evaluate_r001(usd_transfer).matched is False


def test_r001_rejects_missing_required_amount(transaction_factory) -> None:
    with pytest.raises(TypeError, match="amount must be a Decimal"):
        transaction_factory(amount=None)


def test_r001_rejects_naive_transaction_time(transaction_factory) -> None:
    with pytest.raises(ValueError, match="timezone-aware UTC"):
        transaction_factory(occurred_at=datetime(2026, 7, 23, 12, 0))


def test_r001_is_deterministic_and_does_not_mutate_input(transaction_factory) -> None:
    rule_input = RuleEvaluationInput(transaction=transaction_factory())
    original = rule_input

    first = evaluate_r001(rule_input)
    second = evaluate_r001(rule_input)

    assert first == second
    assert rule_input == original
    with pytest.raises(FrozenInstanceError):
        rule_input.transaction.amount = Decimal("1")


def test_r001_ignores_other_rule_behavior_conditions(transaction_factory, event_factory) -> None:
    unrelated_event = event_factory(
        event_type=BehaviorEventType.BENEFICIARY_REGISTERED,
        account_ref="sender-a",
        device_ref=None,
        beneficiary_ref="recipient-a",
    )
    without_events = RuleEvaluationInput(transaction=transaction_factory())
    with_events = RuleEvaluationInput(
        transaction=transaction_factory(),
        behavior_events=(unrelated_event,),
    )

    assert evaluate_r001(with_events) == evaluate_r001(without_events)
