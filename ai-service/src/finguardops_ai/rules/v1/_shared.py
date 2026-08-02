from collections.abc import Iterable
from datetime import datetime, timedelta
from decimal import Decimal

from finguardops_ai.rules.v1.models import (
    BehaviorEventSnapshot,
    TransactionSnapshot,
    TransactionType,
)

AMOUNT_THRESHOLD = Decimal("10000000")
WINDOW_SECONDS = 86_400
TRANSFER_TYPES = frozenset(
    {
        TransactionType.ACCOUNT_TRANSFER,
        TransactionType.OPEN_BANKING_TRANSFER,
    }
)


def is_supported_transfer(transaction: TransactionSnapshot) -> bool:
    return transaction.transaction_type in TRANSFER_TYPES


def is_high_amount_transfer(transaction: TransactionSnapshot) -> bool:
    return (
        is_supported_transfer(transaction)
        and transaction.currency_code == "KRW"
        and transaction.amount >= AMOUNT_THRESHOLD
    )


def is_in_behavior_window(event_at: datetime, cutoff_at: datetime) -> bool:
    window_start = cutoff_at - timedelta(seconds=WINDOW_SECONDS)
    return window_start <= event_at <= cutoff_at


def elapsed_seconds(event_at: datetime, cutoff_at: datetime) -> int:
    elapsed = cutoff_at - event_at
    return elapsed.days * 86_400 + elapsed.seconds


def order_latest(events: Iterable[BehaviorEventSnapshot]) -> tuple[BehaviorEventSnapshot, ...]:
    by_event_id = sorted(events, key=lambda event: event.event_id.int)
    return tuple(sorted(by_event_id, key=lambda event: event.occurred_at, reverse=True))


def select_latest(events: Iterable[BehaviorEventSnapshot]) -> BehaviorEventSnapshot | None:
    ordered = order_latest(events)
    return ordered[0] if ordered else None
