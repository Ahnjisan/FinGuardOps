from dataclasses import FrozenInstanceError
from datetime import datetime, timedelta, timezone
from decimal import Decimal
from uuid import UUID

import pytest

from finguardops_ai.rules.v1.models import (
    BehaviorEventType,
    R004Facts,
    RuleEvaluationInput,
    RuleId,
    TransactionType,
)
from finguardops_ai.rules.v1.r004 import evaluate_r004


def _beneficiary_event(event_factory, **overrides: object):
    values: dict[str, object] = {
        "event_type": BehaviorEventType.BENEFICIARY_REGISTERED,
        "account_ref": "sender-a",
        "device_ref": None,
        "beneficiary_ref": "recipient-a",
    }
    values.update(overrides)
    return event_factory(**values)


def test_r004_matches_recent_same_customer_account_and_beneficiary(
    cutoff_at, transaction_factory, event_factory
) -> None:
    event = _beneficiary_event(event_factory, occurred_at=cutoff_at - timedelta(minutes=30))
    rule_input = RuleEvaluationInput(
        transaction=transaction_factory(),
        behavior_events=(event,),
    )

    result = evaluate_r004(rule_input)

    assert result.rule_id is RuleId.R004
    assert result.matched is True
    assert result.facts == R004Facts(
        event_id=event.event_id,
        beneficiary_registered_at=event.occurred_at,
        elapsed_seconds=1800,
        window_seconds=86400,
    )


def test_r004_does_not_match_different_customer_account_or_beneficiary(
    transaction_factory, event_factory
) -> None:
    events = (
        _beneficiary_event(event_factory, external_customer_ref="customer-b"),
        _beneficiary_event(
            event_factory,
            event_id=UUID("40000000-0000-4000-8000-000000000002"),
            account_ref="sender-b",
        ),
        _beneficiary_event(
            event_factory,
            event_id=UUID("40000000-0000-4000-8000-000000000003"),
            beneficiary_ref="recipient-b",
        ),
    )

    assert (
        evaluate_r004(
            RuleEvaluationInput(transaction=transaction_factory(), behavior_events=events)
        ).matched
        is False
    )


def test_r004_time_window_just_inside_is_included(
    cutoff_at, transaction_factory, event_factory
) -> None:
    event = _beneficiary_event(
        event_factory,
        occurred_at=cutoff_at - timedelta(seconds=86399),
    )

    result = evaluate_r004(
        RuleEvaluationInput(transaction=transaction_factory(), behavior_events=(event,))
    )

    assert result.matched is True
    assert result.facts is not None
    assert result.facts.elapsed_seconds == 86399


def test_r004_time_window_start_and_cutoff_are_included(
    cutoff_at, transaction_factory, event_factory
) -> None:
    at_start = _beneficiary_event(
        event_factory,
        event_id=UUID("40000000-0000-4000-8000-000000000004"),
        occurred_at=cutoff_at - timedelta(seconds=86400),
    )
    at_cutoff = _beneficiary_event(
        event_factory,
        event_id=UUID("40000000-0000-4000-8000-000000000005"),
        occurred_at=cutoff_at,
    )

    result = evaluate_r004(
        RuleEvaluationInput(
            transaction=transaction_factory(),
            behavior_events=(at_start, at_cutoff),
        )
    )

    assert result.matched is True
    assert result.facts is not None
    assert result.facts.event_id == at_cutoff.event_id
    assert result.facts.elapsed_seconds == 0


def test_r004_time_window_exceeded_is_not_matched(
    cutoff_at, transaction_factory, event_factory
) -> None:
    event = _beneficiary_event(
        event_factory,
        occurred_at=cutoff_at - timedelta(seconds=86401),
    )

    assert (
        evaluate_r004(
            RuleEvaluationInput(transaction=transaction_factory(), behavior_events=(event,))
        ).matched
        is False
    )


def test_r004_has_no_amount_or_currency_threshold(transaction_factory, event_factory) -> None:
    rule_input = RuleEvaluationInput(
        transaction=transaction_factory(amount=Decimal("1"), currency_code="USD"),
        behavior_events=(_beneficiary_event(event_factory),),
    )

    assert evaluate_r004(rule_input).matched is True


def test_r004_requires_a_supported_transfer_type(transaction_factory, event_factory) -> None:
    rule_input = RuleEvaluationInput(
        transaction=transaction_factory(
            transaction_type=TransactionType.ATM_WITHDRAWAL,
            recipient_account_ref=None,
        ),
        behavior_events=(_beneficiary_event(event_factory),),
    )

    assert evaluate_r004(rule_input).matched is False


def test_r004_missing_event_reference_is_not_matched(transaction_factory, event_factory) -> None:
    missing_account = _beneficiary_event(event_factory, account_ref=None)
    missing_beneficiary = _beneficiary_event(
        event_factory,
        event_id=UUID("40000000-0000-4000-8000-000000000006"),
        beneficiary_ref=None,
    )

    assert (
        evaluate_r004(
            RuleEvaluationInput(
                transaction=transaction_factory(),
                behavior_events=(missing_account, missing_beneficiary),
            )
        ).matched
        is False
    )


def test_r004_rejects_non_utc_behavior_time(event_factory) -> None:
    with pytest.raises(ValueError, match="must use UTC"):
        _beneficiary_event(
            event_factory,
            occurred_at=datetime(2026, 7, 23, 21, 0, tzinfo=timezone(timedelta(hours=9))),
        )


def test_r004_selects_latest_event_then_lowest_event_id(
    cutoff_at, transaction_factory, event_factory
) -> None:
    high_id = _beneficiary_event(
        event_factory,
        event_id=UUID("40000000-0000-4000-8000-000000000009"),
        occurred_at=cutoff_at - timedelta(minutes=1),
    )
    low_id = _beneficiary_event(
        event_factory,
        event_id=UUID("40000000-0000-4000-8000-000000000008"),
        occurred_at=cutoff_at - timedelta(minutes=1),
    )

    result = evaluate_r004(
        RuleEvaluationInput(
            transaction=transaction_factory(),
            behavior_events=(high_id, low_id),
        )
    )

    assert result.facts is not None
    assert result.facts.event_id == low_id.event_id


def test_r004_is_deterministic_and_does_not_mutate_input(
    transaction_factory, event_factory
) -> None:
    rule_input = RuleEvaluationInput(
        transaction=transaction_factory(),
        behavior_events=(_beneficiary_event(event_factory),),
    )
    original_events = rule_input.behavior_events

    first = evaluate_r004(rule_input)
    second = evaluate_r004(rule_input)

    assert first == second
    assert rule_input.behavior_events == original_events
    with pytest.raises(FrozenInstanceError):
        rule_input.behavior_events = ()


def test_r004_ignores_other_rule_events(transaction_factory, event_factory) -> None:
    beneficiary = _beneficiary_event(event_factory)
    device = event_factory(
        event_id=UUID("40000000-0000-4000-8000-000000000010"),
        event_type=BehaviorEventType.DEVICE_REGISTERED,
    )
    base_input = RuleEvaluationInput(
        transaction=transaction_factory(),
        behavior_events=(beneficiary,),
    )
    mixed_input = RuleEvaluationInput(
        transaction=transaction_factory(),
        behavior_events=(device, beneficiary),
    )

    assert evaluate_r004(mixed_input) == evaluate_r004(base_input)
