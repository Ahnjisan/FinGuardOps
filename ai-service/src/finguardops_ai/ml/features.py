"""Versioned, cutoff-safe feature extraction for the local ML baseline."""

from datetime import datetime, timedelta
from decimal import Decimal
from math import log1p

FEATURE_VERSION = "fraud-feature-v1"
FEATURE_NAMES = (
    "log_amount",
    "is_transfer",
    "is_mobile",
    "device_registered_24h",
    "password_changed_24h",
    "limit_changed_24h",
    "beneficiary_registered_24h",
)
EVENT_TYPES = (
    "DEVICE_REGISTERED",
    "PASSWORD_CHANGED",
    "TRANSFER_LIMIT_CHANGED",
    "BENEFICIARY_REGISTERED",
)


def extract_features(
    *,
    amount: Decimal,
    transaction_type: str,
    channel: str,
    cutoff: datetime,
    events: tuple[object, ...],
) -> tuple[float, ...]:
    if not amount.is_finite() or amount < 0 or amount > Decimal("999999999999999"):
        raise ValueError("INVALID_AMOUNT")
    if transaction_type not in (
        "ACCOUNT_TRANSFER",
        "OPEN_BANKING_TRANSFER",
        "ATM_WITHDRAWAL",
        "LOAN_DISBURSED",
    ):
        raise ValueError("INVALID_TRANSACTION_TYPE")
    if channel not in ("MOBILE_BANKING", "OPEN_BANKING", "ATM", "CORE_BANKING"):
        raise ValueError("INVALID_CHANNEL")
    if cutoff.tzinfo is None:
        raise ValueError("INVALID_CUTOFF")
    lower = cutoff - timedelta(hours=24)
    seen: set[str] = set()
    counts = {name: 0 for name in EVENT_TYPES}
    for event in events:
        event_id = str(event.event_id)
        if event_id in seen:
            raise ValueError("DUPLICATE_EVENT")
        seen.add(event_id)
        if event.event_type not in counts:
            raise ValueError("INVALID_EVENT_TYPE")
        if not lower <= event.occurred_at <= cutoff or event.created_at > cutoff:
            raise ValueError("EVENT_OUTSIDE_CUTOFF")
        counts[event.event_type] += 1
    return (
        log1p(float(amount)) / 20.0,
        float(transaction_type in ("ACCOUNT_TRANSFER", "OPEN_BANKING_TRANSFER")),
        float(channel == "MOBILE_BANKING"),
        min(counts["DEVICE_REGISTERED"], 3) / 3.0,
        min(counts["PASSWORD_CHANGED"], 3) / 3.0,
        min(counts["TRANSFER_LIMIT_CHANGED"], 3) / 3.0,
        min(counts["BENEFICIARY_REGISTERED"], 3) / 3.0,
    )
