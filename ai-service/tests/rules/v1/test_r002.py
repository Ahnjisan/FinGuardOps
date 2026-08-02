from dataclasses import FrozenInstanceError
from datetime import datetime, timedelta, timezone
from decimal import Decimal
from uuid import UUID

import pytest

from finguardops_ai.rules.v1.models import (
    BehaviorEventType,
    R002Facts,
    RuleEvaluationInput,
    RuleId,
)
from finguardops_ai.rules.v1.r002 import evaluate_r002


def test_r002_matches_recent_same_customer_and_device(
    cutoff_at, transaction_factory, event_factory
) -> None:
    event = event_factory(occurred_at=cutoff_at - timedelta(hours=1))
    rule_input = RuleEvaluationInput(
        transaction=transaction_factory(),
        behavior_events=(event,),
    )

    result = evaluate_r002(rule_input)

    assert result.rule_id is RuleId.R002
    assert result.matched is True
    assert result.facts == R002Facts(
        observed_amount=Decimal("10000000"),
        amount_threshold=Decimal("10000000"),
        event_id=event.event_id,
        device_registered_at=event.occurred_at,
        elapsed_seconds=3600,
        window_seconds=86400,
    )


def test_r002_does_not_match_different_customer_or_device(
    transaction_factory, event_factory
) -> None:
    different_customer = event_factory(external_customer_ref="customer-b")
    different_device = event_factory(device_ref="device-b")

    assert (
        evaluate_r002(
            RuleEvaluationInput(
                transaction=transaction_factory(),
                behavior_events=(different_customer, different_device),
            )
        ).matched
        is False
    )


def test_r002_does_not_match_immediately_below_amount_threshold(
    transaction_factory, event_factory
) -> None:
    rule_input = RuleEvaluationInput(
        transaction=transaction_factory(amount=Decimal("9999999")),
        behavior_events=(event_factory(),),
    )

    assert evaluate_r002(rule_input).matched is False


def test_r002_matches_at_and_above_amount_threshold(transaction_factory, event_factory) -> None:
    event = event_factory()
    at_threshold = RuleEvaluationInput(
        transaction=transaction_factory(amount=Decimal("10000000")),
        behavior_events=(event,),
    )
    above_threshold = RuleEvaluationInput(
        transaction=transaction_factory(amount=Decimal("10000001")),
        behavior_events=(event,),
    )

    assert evaluate_r002(at_threshold).matched is True
    assert evaluate_r002(above_threshold).matched is True


def test_r002_time_window_just_inside_is_included(
    cutoff_at, transaction_factory, event_factory
) -> None:
    event = event_factory(occurred_at=cutoff_at - timedelta(seconds=86399))

    result = evaluate_r002(
        RuleEvaluationInput(transaction=transaction_factory(), behavior_events=(event,))
    )

    assert result.matched is True
    assert result.facts is not None
    assert result.facts.elapsed_seconds == 86399


def test_r002_time_window_start_and_cutoff_are_included(
    cutoff_at, transaction_factory, event_factory
) -> None:
    at_start = event_factory(
        event_id=UUID("20000000-0000-4000-8000-000000000002"),
        occurred_at=cutoff_at - timedelta(seconds=86400),
    )
    at_cutoff = event_factory(
        event_id=UUID("20000000-0000-4000-8000-000000000003"),
        occurred_at=cutoff_at,
    )

    result = evaluate_r002(
        RuleEvaluationInput(
            transaction=transaction_factory(),
            behavior_events=(at_start, at_cutoff),
        )
    )

    assert result.matched is True
    assert result.facts is not None
    assert result.facts.event_id == at_cutoff.event_id
    assert result.facts.elapsed_seconds == 0


def test_r002_time_window_exceeded_is_not_matched(
    cutoff_at, transaction_factory, event_factory
) -> None:
    event = event_factory(occurred_at=cutoff_at - timedelta(seconds=86401))

    assert (
        evaluate_r002(
            RuleEvaluationInput(transaction=transaction_factory(), behavior_events=(event,))
        ).matched
        is False
    )


def test_r002_missing_device_is_not_matched(transaction_factory, event_factory) -> None:
    missing_transaction_device = RuleEvaluationInput(
        transaction=transaction_factory(device_ref=None),
        behavior_events=(event_factory(),),
    )
    missing_event_device = RuleEvaluationInput(
        transaction=transaction_factory(),
        behavior_events=(event_factory(device_ref=None),),
    )

    assert evaluate_r002(missing_transaction_device).matched is False
    assert evaluate_r002(missing_event_device).matched is False


def test_r002_rejects_non_utc_event_time(event_factory) -> None:
    with pytest.raises(ValueError, match="must use UTC"):
        event_factory(occurred_at=datetime(2026, 7, 23, 21, 0, tzinfo=timezone(timedelta(hours=9))))


def test_r002_selects_latest_event_then_lowest_event_id(
    cutoff_at, transaction_factory, event_factory
) -> None:
    later_high_id = event_factory(
        event_id=UUID("20000000-0000-4000-8000-000000000009"),
        occurred_at=cutoff_at - timedelta(minutes=1),
    )
    later_low_id = event_factory(
        event_id=UUID("20000000-0000-4000-8000-000000000008"),
        occurred_at=cutoff_at - timedelta(minutes=1),
    )
    earlier = event_factory(
        event_id=UUID("20000000-0000-4000-8000-000000000001"),
        occurred_at=cutoff_at - timedelta(minutes=2),
    )

    result = evaluate_r002(
        RuleEvaluationInput(
            transaction=transaction_factory(),
            behavior_events=(later_high_id, earlier, later_low_id),
        )
    )

    assert result.facts is not None
    assert result.facts.event_id == later_low_id.event_id


def test_r002_is_deterministic_and_does_not_mutate_input(
    transaction_factory, event_factory
) -> None:
    rule_input = RuleEvaluationInput(
        transaction=transaction_factory(),
        behavior_events=(event_factory(),),
    )
    original_events = rule_input.behavior_events

    first = evaluate_r002(rule_input)
    second = evaluate_r002(rule_input)

    assert first == second
    assert rule_input.behavior_events == original_events
    with pytest.raises(FrozenInstanceError):
        rule_input.behavior_events = ()


def test_r002_ignores_other_rule_events(transaction_factory, event_factory) -> None:
    device_event = event_factory()
    unrelated_event = event_factory(
        event_id=UUID("20000000-0000-4000-8000-000000000004"),
        event_type=BehaviorEventType.PASSWORD_CHANGED,
        device_ref=None,
    )
    base_input = RuleEvaluationInput(
        transaction=transaction_factory(),
        behavior_events=(device_event,),
    )
    mixed_input = RuleEvaluationInput(
        transaction=transaction_factory(),
        behavior_events=(unrelated_event, device_event),
    )

    assert evaluate_r002(mixed_input) == evaluate_r002(base_input)
