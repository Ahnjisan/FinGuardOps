from dataclasses import dataclass
from datetime import datetime, timedelta
from decimal import Decimal
from enum import StrEnum
from uuid import RFC_4122, UUID

MAX_TRANSACTION_AMOUNT = Decimal("999999999999999")


class TransactionType(StrEnum):
    """Transaction types accepted by the transaction intake contract."""

    ACCOUNT_TRANSFER = "ACCOUNT_TRANSFER"
    OPEN_BANKING_TRANSFER = "OPEN_BANKING_TRANSFER"
    ATM_WITHDRAWAL = "ATM_WITHDRAWAL"
    LOAN_DISBURSED = "LOAN_DISBURSED"


class BehaviorEventType(StrEnum):
    """Behavior event types used by Rule v1."""

    DEVICE_REGISTERED = "DEVICE_REGISTERED"
    PASSWORD_CHANGED = "PASSWORD_CHANGED"
    TRANSFER_LIMIT_CHANGED = "TRANSFER_LIMIT_CHANGED"
    BENEFICIARY_REGISTERED = "BENEFICIARY_REGISTERED"


class RuleId(StrEnum):
    """Document aliases for the four Rule v1 evaluators."""

    R001 = "R001"
    R002 = "R002"
    R003 = "R003"
    R004 = "R004"


def _require_uuid_v4(value: object, field_name: str) -> None:
    if not isinstance(value, UUID):
        raise TypeError(f"{field_name} must be a UUID")
    if value.version != 4 or value.variant != RFC_4122:
        raise ValueError(f"{field_name} must be an RFC 4122 UUID v4")


def _require_utc_datetime(value: object, field_name: str) -> None:
    if not isinstance(value, datetime):
        raise TypeError(f"{field_name} must be a datetime")
    if value.tzinfo is None or value.utcoffset() is None:
        raise ValueError(f"{field_name} must be timezone-aware UTC")
    if value.utcoffset() != timedelta(0):
        raise ValueError(f"{field_name} must use UTC")


def _require_reference(value: object, field_name: str) -> None:
    if not isinstance(value, str):
        raise TypeError(f"{field_name} must be a string")
    if not 1 <= len(value) <= 128 or value != value.strip():
        raise ValueError(f"{field_name} must be 1 to 128 unpadded characters")


def _require_optional_reference(value: object, field_name: str) -> None:
    if value is not None:
        _require_reference(value, field_name)


@dataclass(frozen=True, slots=True)
class TransactionSnapshot:
    """Immutable minimum transaction snapshot shared by Rule v1 evaluators."""

    transaction_id: UUID
    transaction_type: TransactionType
    amount: Decimal
    currency_code: str
    occurred_at: datetime
    external_customer_ref: str
    sender_account_ref: str
    recipient_account_ref: str | None
    device_ref: str | None

    def __post_init__(self) -> None:
        _require_uuid_v4(self.transaction_id, "transaction_id")
        if not isinstance(self.transaction_type, TransactionType):
            raise TypeError("transaction_type must be a TransactionType")
        if not isinstance(self.amount, Decimal):
            raise TypeError("amount must be a Decimal")
        if (
            not self.amount.is_finite()
            or self.amount <= 0
            or self.amount != self.amount.to_integral_value()
            or self.amount > MAX_TRANSACTION_AMOUNT
        ):
            raise ValueError("amount must be a positive integer within NUMERIC(19,4)")
        if (
            not isinstance(self.currency_code, str)
            or len(self.currency_code) != 3
            or not self.currency_code.isascii()
            or not self.currency_code.isalpha()
            or self.currency_code != self.currency_code.upper()
        ):
            raise ValueError("currency_code must be a three-letter uppercase ASCII code")
        _require_utc_datetime(self.occurred_at, "occurred_at")
        _require_reference(self.external_customer_ref, "external_customer_ref")
        _require_reference(self.sender_account_ref, "sender_account_ref")
        _require_optional_reference(self.recipient_account_ref, "recipient_account_ref")
        _require_optional_reference(self.device_ref, "device_ref")

        is_transfer = self.transaction_type in {
            TransactionType.ACCOUNT_TRANSFER,
            TransactionType.OPEN_BANKING_TRANSFER,
        }
        if is_transfer and self.recipient_account_ref is None:
            raise ValueError("recipient_account_ref is required for transfer transactions")
        if not is_transfer and self.recipient_account_ref is not None:
            raise ValueError("recipient_account_ref is forbidden for non-transfer transactions")


@dataclass(frozen=True, slots=True)
class BehaviorEventSnapshot:
    """Immutable minimum behavior event snapshot used by Rule v1."""

    event_id: UUID
    event_type: BehaviorEventType
    occurred_at: datetime
    external_customer_ref: str
    account_ref: str | None = None
    device_ref: str | None = None
    beneficiary_ref: str | None = None

    def __post_init__(self) -> None:
        _require_uuid_v4(self.event_id, "event_id")
        if not isinstance(self.event_type, BehaviorEventType):
            raise TypeError("event_type must be a BehaviorEventType")
        _require_utc_datetime(self.occurred_at, "occurred_at")
        _require_reference(self.external_customer_ref, "external_customer_ref")
        _require_optional_reference(self.account_ref, "account_ref")
        _require_optional_reference(self.device_ref, "device_ref")
        _require_optional_reference(self.beneficiary_ref, "beneficiary_ref")


@dataclass(frozen=True, slots=True)
class RuleEvaluationInput:
    """Immutable input for one Rule v1 evaluator invocation."""

    transaction: TransactionSnapshot
    behavior_events: tuple[BehaviorEventSnapshot, ...] = ()

    def __post_init__(self) -> None:
        if not isinstance(self.transaction, TransactionSnapshot):
            raise TypeError("transaction must be a TransactionSnapshot")
        if not isinstance(self.behavior_events, tuple):
            raise TypeError("behavior_events must be a tuple")
        if not all(isinstance(event, BehaviorEventSnapshot) for event in self.behavior_events):
            raise TypeError("behavior_events must contain only BehaviorEventSnapshot values")


@dataclass(frozen=True, slots=True)
class R001Facts:
    observed_amount: Decimal
    amount_threshold: Decimal


@dataclass(frozen=True, slots=True)
class R002Facts:
    observed_amount: Decimal
    amount_threshold: Decimal
    event_id: UUID
    device_registered_at: datetime
    elapsed_seconds: int
    window_seconds: int


@dataclass(frozen=True, slots=True)
class R003Facts:
    observed_amount: Decimal
    amount_threshold: Decimal
    password_changed_event_id: UUID
    password_changed_at: datetime
    transfer_limit_changed_event_id: UUID
    transfer_limit_changed_at: datetime
    elapsed_seconds: int
    window_seconds: int


@dataclass(frozen=True, slots=True)
class R004Facts:
    observed_amount: Decimal
    event_id: UUID
    beneficiary_registered_at: datetime
    elapsed_seconds: int
    window_seconds: int


@dataclass(frozen=True, slots=True)
class RuleEvaluationResult[FactT]:
    """Minimal internal result with facts only when a Rule matched."""

    rule_id: RuleId
    matched: bool
    facts: FactT | None

    def __post_init__(self) -> None:
        if self.matched != (self.facts is not None):
            raise ValueError("matched must be true exactly when facts are present")
