import re
from collections.abc import Mapping
from copy import deepcopy
from dataclasses import dataclass
from decimal import Decimal

from finguardops_ai.rules.v1._shared import (
    AMOUNT_THRESHOLD,
    TRANSFER_TYPES,
    WINDOW_SECONDS,
)
from finguardops_ai.rules.v1.models import BehaviorEventType, RuleId, TransactionType

_HIGH_AMOUNT_RULE_CODE = "TRANSFER_ABSOLUTE_HIGH_AMOUNT"
_LATEST_EVENT_SELECTION = "LATEST_OCCURRED_AT_THEN_EVENT_ID_ASC"
_SECURITY_SELECTION = "LATEST_TRANSFER_LIMIT_THEN_EVENT_ID_ASC_LATEST_PASSWORD_THEN_EVENT_ID_ASC"
_AMOUNT_PATTERN = re.compile(r"^[1-9][0-9]{0,14}$")


class InvalidConditionDefinitionError(ValueError):
    """Raised internally when a RuleVersion condition definition is unsupported."""


@dataclass(frozen=True, slots=True)
class R001ConditionDefinition:
    transaction_types: frozenset[TransactionType]
    currency_code: str
    amount_threshold: Decimal


@dataclass(frozen=True, slots=True)
class R002ConditionDefinition:
    prerequisite_rule_code: str
    event_type: BehaviorEventType
    window_seconds: int
    match_policy: str
    selection_policy: str


@dataclass(frozen=True, slots=True)
class R003ConditionDefinition:
    prerequisite_rule_code: str
    password_event_type: BehaviorEventType
    transfer_limit_event_type: BehaviorEventType
    window_seconds: int
    match_policy: str
    sequence_policy: str
    selection_policy: str


@dataclass(frozen=True, slots=True)
class R004ConditionDefinition:
    event_type: BehaviorEventType
    window_seconds: int
    match_policy: str
    selection_policy: str


type RuleConditionDefinition = (
    R001ConditionDefinition
    | R002ConditionDefinition
    | R003ConditionDefinition
    | R004ConditionDefinition
)


def parse_condition_definition(
    rule_id: RuleId,
    value: object,
) -> RuleConditionDefinition:
    """Deep-copy, strictly parse, and validate one supported Rule v1 configuration."""
    root = _copy_json_object(value)
    match rule_id:
        case RuleId.R001:
            return _parse_r001(root)
        case RuleId.R002:
            return _parse_r002(root)
        case RuleId.R003:
            return _parse_r003(root)
        case RuleId.R004:
            return _parse_r004(root)
    raise InvalidConditionDefinitionError(f"Unsupported Rule ID: {rule_id!r}")


def _copy_json_object(value: object) -> dict[object, object]:
    if not isinstance(value, Mapping):
        raise InvalidConditionDefinitionError("condition_definition must be an object")
    try:
        return deepcopy(dict(value))
    except Exception as exc:
        raise InvalidConditionDefinitionError(
            "condition_definition could not be copied safely"
        ) from exc


def _parse_r001(root: dict[object, object]) -> R001ConditionDefinition:
    _require_exact_fields(root, {"transactionTypes", "currencyCode", "amountThreshold"})
    transaction_types = _require_transaction_types(root["transactionTypes"])
    currency_code = _require_exact_text(root, "currencyCode", "KRW")
    amount_threshold = _require_amount_threshold(root["amountThreshold"])
    return R001ConditionDefinition(
        transaction_types=transaction_types,
        currency_code=currency_code,
        amount_threshold=amount_threshold,
    )


def _parse_r002(root: dict[object, object]) -> R002ConditionDefinition:
    _require_exact_fields(
        root,
        {
            "prerequisiteRuleCode",
            "eventType",
            "windowSeconds",
            "matchPolicy",
            "selectionPolicy",
        },
    )
    return R002ConditionDefinition(
        prerequisite_rule_code=_require_exact_text(
            root, "prerequisiteRuleCode", _HIGH_AMOUNT_RULE_CODE
        ),
        event_type=_require_event_type(root, "eventType", BehaviorEventType.DEVICE_REGISTERED),
        window_seconds=_require_window_seconds(root["windowSeconds"]),
        match_policy=_require_exact_text(root, "matchPolicy", "SAME_CUSTOMER_AND_DEVICE"),
        selection_policy=_require_exact_text(root, "selectionPolicy", _LATEST_EVENT_SELECTION),
    )


def _parse_r003(root: dict[object, object]) -> R003ConditionDefinition:
    _require_exact_fields(
        root,
        {
            "prerequisiteRuleCode",
            "passwordEventType",
            "transferLimitEventType",
            "windowSeconds",
            "matchPolicy",
            "sequencePolicy",
            "selectionPolicy",
        },
    )
    return R003ConditionDefinition(
        prerequisite_rule_code=_require_exact_text(
            root, "prerequisiteRuleCode", _HIGH_AMOUNT_RULE_CODE
        ),
        password_event_type=_require_event_type(
            root, "passwordEventType", BehaviorEventType.PASSWORD_CHANGED
        ),
        transfer_limit_event_type=_require_event_type(
            root,
            "transferLimitEventType",
            BehaviorEventType.TRANSFER_LIMIT_CHANGED,
        ),
        window_seconds=_require_window_seconds(root["windowSeconds"]),
        match_policy=_require_exact_text(root, "matchPolicy", "SAME_CUSTOMER_AND_SENDER_ACCOUNT"),
        sequence_policy=_require_exact_text(
            root,
            "sequencePolicy",
            "PASSWORD_CHANGED_AT_OR_BEFORE_TRANSFER_LIMIT_CHANGED",
        ),
        selection_policy=_require_exact_text(root, "selectionPolicy", _SECURITY_SELECTION),
    )


def _parse_r004(root: dict[object, object]) -> R004ConditionDefinition:
    _require_exact_fields(
        root,
        {"eventType", "windowSeconds", "matchPolicy", "selectionPolicy"},
    )
    return R004ConditionDefinition(
        event_type=_require_event_type(root, "eventType", BehaviorEventType.BENEFICIARY_REGISTERED),
        window_seconds=_require_window_seconds(root["windowSeconds"]),
        match_policy=_require_exact_text(
            root,
            "matchPolicy",
            "SAME_CUSTOMER_SENDER_ACCOUNT_AND_BENEFICIARY",
        ),
        selection_policy=_require_exact_text(root, "selectionPolicy", _LATEST_EVENT_SELECTION),
    )


def _require_exact_fields(root: dict[object, object], expected: set[str]) -> None:
    if set(root) != expected:
        raise InvalidConditionDefinitionError(
            "condition_definition fields do not match the Rule v1 contract"
        )


def _require_exact_text(root: dict[object, object], field: str, expected: str) -> str:
    value = root[field]
    if not isinstance(value, str) or value != expected:
        raise InvalidConditionDefinitionError(f"{field} must equal {expected!r}")
    return value


def _require_event_type(
    root: dict[object, object],
    field: str,
    expected: BehaviorEventType,
) -> BehaviorEventType:
    _require_exact_text(root, field, expected.value)
    return expected


def _require_window_seconds(value: object) -> int:
    if type(value) is not int or value != WINDOW_SECONDS:
        raise InvalidConditionDefinitionError(f"windowSeconds must equal integer {WINDOW_SECONDS}")
    return value


def _require_amount_threshold(value: object) -> Decimal:
    if not isinstance(value, str) or _AMOUNT_PATTERN.fullmatch(value) is None:
        raise InvalidConditionDefinitionError(
            "amountThreshold must be a canonical positive decimal integer string"
        )
    amount = Decimal(value)
    if amount != AMOUNT_THRESHOLD:
        raise InvalidConditionDefinitionError(
            f"amountThreshold must equal {str(AMOUNT_THRESHOLD)!r}"
        )
    return amount


def _require_transaction_types(value: object) -> frozenset[TransactionType]:
    if not isinstance(value, list) or not value:
        raise InvalidConditionDefinitionError("transactionTypes must be a non-empty array")
    if not all(isinstance(item, str) for item in value):
        raise InvalidConditionDefinitionError("transactionTypes must contain only strings")
    if len(set(value)) != len(value):
        raise InvalidConditionDefinitionError("transactionTypes must contain unique strings")
    try:
        transaction_types = frozenset(TransactionType(item) for item in value)
    except ValueError as exc:
        raise InvalidConditionDefinitionError(
            "transactionTypes contains an unsupported transaction type"
        ) from exc
    if transaction_types != TRANSFER_TYPES:
        raise InvalidConditionDefinitionError(
            "transactionTypes does not match the evaluator-supported transfer types"
        )
    return transaction_types
