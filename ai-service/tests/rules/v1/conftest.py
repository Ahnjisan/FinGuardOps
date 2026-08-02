from datetime import UTC, datetime
from decimal import Decimal
from uuid import UUID

import pytest

from finguardops_ai.rules.v1.models import (
    BehaviorEventSnapshot,
    BehaviorEventType,
    TransactionSnapshot,
    TransactionType,
)

CUTOFF_AT = datetime(2026, 7, 23, 12, 0, tzinfo=UTC)


@pytest.fixture
def cutoff_at() -> datetime:
    return CUTOFF_AT


@pytest.fixture
def transaction_factory():
    def factory(**overrides: object) -> TransactionSnapshot:
        values: dict[str, object] = {
            "transaction_id": UUID("10000000-0000-4000-8000-000000000001"),
            "transaction_type": TransactionType.ACCOUNT_TRANSFER,
            "amount": Decimal("10000000"),
            "currency_code": "KRW",
            "occurred_at": CUTOFF_AT,
            "external_customer_ref": "customer-a",
            "sender_account_ref": "sender-a",
            "recipient_account_ref": "recipient-a",
            "device_ref": "device-a",
        }
        values.update(overrides)
        return TransactionSnapshot(**values)  # type: ignore[arg-type]

    return factory


@pytest.fixture
def event_factory():
    def factory(**overrides: object) -> BehaviorEventSnapshot:
        values: dict[str, object] = {
            "event_id": UUID("20000000-0000-4000-8000-000000000001"),
            "event_type": BehaviorEventType.DEVICE_REGISTERED,
            "occurred_at": CUTOFF_AT,
            "external_customer_ref": "customer-a",
            "account_ref": None,
            "device_ref": "device-a",
            "beneficiary_ref": None,
        }
        values.update(overrides)
        return BehaviorEventSnapshot(**values)  # type: ignore[arg-type]

    return factory
