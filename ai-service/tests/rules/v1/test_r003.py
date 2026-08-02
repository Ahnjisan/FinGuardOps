from dataclasses import FrozenInstanceError
from datetime import datetime, timedelta
from decimal import Decimal
from uuid import UUID

import pytest

from finguardops_ai.rules.v1.models import BehaviorEventType, RuleEvaluationInput, RuleId
from finguardops_ai.rules.v1.r003 import evaluate_r003


def _password_event(event_factory, **overrides: object):
    values: dict[str, object] = {
        "event_id": UUID("30000000-0000-4000-8000-000000000001"),
        "event_type": BehaviorEventType.PASSWORD_CHANGED,
        "device_ref": None,
    }
    values.update(overrides)
    return event_factory(**values)


def _limit_event(event_factory, **overrides: object):
    values: dict[str, object] = {
        "event_id": UUID("30000000-0000-4000-8000-000000000002"),
        "event_type": BehaviorEventType.TRANSFER_LIMIT_CHANGED,
        "account_ref": "sender-a",
        "device_ref": None,
    }
    values.update(overrides)
    return event_factory(**values)


def test_r003_matches_ordered_security_events(
    cutoff_at, transaction_factory, event_factory
) -> None:
    password = _password_event(event_factory, occurred_at=cutoff_at - timedelta(hours=2))
    limit = _limit_event(event_factory, occurred_at=cutoff_at - timedelta(hours=1))
    rule_input = RuleEvaluationInput(
        transaction=transaction_factory(),
        behavior_events=(limit, password),
    )

    result = evaluate_r003(rule_input)

    assert result.rule_id is RuleId.R003
    assert result.matched is True
    assert result.facts is not None
    assert result.facts.observed_amount == Decimal("10000000")
    assert result.facts.amount_threshold == Decimal("10000000")
    assert result.facts.password_changed_event_id == password.event_id
    assert result.facts.transfer_limit_changed_event_id == limit.event_id
    assert result.facts.elapsed_seconds == 3600
    assert result.facts.window_seconds == 86400


def test_r003_does_not_match_when_an_event_is_missing(transaction_factory, event_factory) -> None:
    only_password = RuleEvaluationInput(
        transaction=transaction_factory(),
        behavior_events=(_password_event(event_factory),),
    )

    assert evaluate_r003(only_password).matched is False


def test_r003_does_not_match_immediately_below_amount_threshold(
    transaction_factory, event_factory
) -> None:
    rule_input = RuleEvaluationInput(
        transaction=transaction_factory(amount=Decimal("9999999")),
        behavior_events=(_password_event(event_factory), _limit_event(event_factory)),
    )

    assert evaluate_r003(rule_input).matched is False


def test_r003_matches_at_and_above_amount_threshold(transaction_factory, event_factory) -> None:
    events = (_password_event(event_factory), _limit_event(event_factory))
    at_threshold = RuleEvaluationInput(
        transaction=transaction_factory(amount=Decimal("10000000")),
        behavior_events=events,
    )
    above_threshold = RuleEvaluationInput(
        transaction=transaction_factory(amount=Decimal("10000001")),
        behavior_events=events,
    )

    assert evaluate_r003(at_threshold).matched is True
    assert evaluate_r003(above_threshold).matched is True


def test_r003_time_window_just_inside_is_included(
    cutoff_at, transaction_factory, event_factory
) -> None:
    password = _password_event(
        event_factory,
        occurred_at=cutoff_at - timedelta(seconds=86399),
    )
    limit = _limit_event(
        event_factory,
        occurred_at=cutoff_at - timedelta(seconds=86398),
    )

    assert (
        evaluate_r003(
            RuleEvaluationInput(
                transaction=transaction_factory(),
                behavior_events=(password, limit),
            )
        ).matched
        is True
    )


def test_r003_time_window_start_and_cutoff_are_included(
    cutoff_at, transaction_factory, event_factory
) -> None:
    password = _password_event(
        event_factory,
        occurred_at=cutoff_at - timedelta(seconds=86400),
    )
    limit = _limit_event(event_factory, occurred_at=cutoff_at)

    result = evaluate_r003(
        RuleEvaluationInput(
            transaction=transaction_factory(),
            behavior_events=(password, limit),
        )
    )

    assert result.matched is True
    assert result.facts is not None
    assert result.facts.elapsed_seconds == 0


def test_r003_time_window_exceeded_is_not_matched(
    cutoff_at, transaction_factory, event_factory
) -> None:
    password = _password_event(
        event_factory,
        occurred_at=cutoff_at - timedelta(seconds=86401),
    )
    limit = _limit_event(event_factory, occurred_at=cutoff_at - timedelta(hours=1))

    assert (
        evaluate_r003(
            RuleEvaluationInput(
                transaction=transaction_factory(),
                behavior_events=(password, limit),
            )
        ).matched
        is False
    )


def test_r003_rejects_reversed_event_sequence(
    cutoff_at, transaction_factory, event_factory
) -> None:
    password = _password_event(event_factory, occurred_at=cutoff_at - timedelta(minutes=1))
    limit = _limit_event(event_factory, occurred_at=cutoff_at - timedelta(minutes=2))

    assert (
        evaluate_r003(
            RuleEvaluationInput(
                transaction=transaction_factory(),
                behavior_events=(password, limit),
            )
        ).matched
        is False
    )


def test_r003_missing_limit_account_is_not_matched(transaction_factory, event_factory) -> None:
    rule_input = RuleEvaluationInput(
        transaction=transaction_factory(),
        behavior_events=(
            _password_event(event_factory),
            _limit_event(event_factory, account_ref=None),
        ),
    )

    assert evaluate_r003(rule_input).matched is False


def test_r003_rejects_naive_behavior_time(event_factory) -> None:
    with pytest.raises(ValueError, match="timezone-aware UTC"):
        _password_event(event_factory, occurred_at=datetime(2026, 7, 23, 10, 0))


def test_r003_uses_latest_eligible_limit_and_password(
    cutoff_at, transaction_factory, event_factory
) -> None:
    older_password = _password_event(
        event_factory,
        event_id=UUID("30000000-0000-4000-8000-000000000009"),
        occurred_at=cutoff_at - timedelta(hours=3),
    )
    latest_password_high_id = _password_event(
        event_factory,
        event_id=UUID("30000000-0000-4000-8000-000000000008"),
        occurred_at=cutoff_at - timedelta(hours=2),
    )
    latest_password_low_id = _password_event(
        event_factory,
        event_id=UUID("30000000-0000-4000-8000-000000000007"),
        occurred_at=cutoff_at - timedelta(hours=2),
    )
    latest_limit_high_id = _limit_event(
        event_factory,
        event_id=UUID("30000000-0000-4000-8000-000000000006"),
        occurred_at=cutoff_at - timedelta(hours=1),
    )
    latest_limit_low_id = _limit_event(
        event_factory,
        event_id=UUID("30000000-0000-4000-8000-000000000005"),
        occurred_at=cutoff_at - timedelta(hours=1),
    )

    result = evaluate_r003(
        RuleEvaluationInput(
            transaction=transaction_factory(),
            behavior_events=(
                latest_limit_high_id,
                older_password,
                latest_password_high_id,
                latest_limit_low_id,
                latest_password_low_id,
            ),
        )
    )

    assert result.facts is not None
    assert result.facts.transfer_limit_changed_event_id == latest_limit_low_id.event_id
    assert result.facts.password_changed_event_id == latest_password_low_id.event_id


def test_r003_is_deterministic_and_does_not_mutate_input(
    transaction_factory, event_factory
) -> None:
    rule_input = RuleEvaluationInput(
        transaction=transaction_factory(),
        behavior_events=(_password_event(event_factory), _limit_event(event_factory)),
    )
    original_events = rule_input.behavior_events

    first = evaluate_r003(rule_input)
    second = evaluate_r003(rule_input)

    assert first == second
    assert rule_input.behavior_events == original_events
    with pytest.raises(FrozenInstanceError):
        rule_input.behavior_events = ()


def test_r003_ignores_other_rule_events(transaction_factory, event_factory) -> None:
    password = _password_event(event_factory)
    limit = _limit_event(event_factory)
    device = event_factory(
        event_id=UUID("30000000-0000-4000-8000-000000000010"),
        event_type=BehaviorEventType.DEVICE_REGISTERED,
    )
    base_input = RuleEvaluationInput(
        transaction=transaction_factory(),
        behavior_events=(password, limit),
    )
    mixed_input = RuleEvaluationInput(
        transaction=transaction_factory(),
        behavior_events=(device, password, limit),
    )

    assert evaluate_r003(mixed_input) == evaluate_r003(base_input)
