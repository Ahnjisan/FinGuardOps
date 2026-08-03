from dataclasses import replace
from datetime import UTC, datetime, timedelta
from decimal import Decimal
from uuid import UUID

import pytest

from finguardops_ai.rules.v1 import (
    BehaviorEventType,
    FraudRuleLifecycleStatus,
    R001ConditionDefinition,
    R002ConditionDefinition,
    R003ConditionDefinition,
    R004ConditionDefinition,
    RuleExecutionPlan,
    RuleExecutionPlanBuilder,
    RuleExecutionPlanError,
    RuleExecutionPlanErrorCategory,
    RuleId,
    RuleVersionSnapshot,
    RuleVersionStatus,
    TransactionType,
    create_default_rule_evaluator_registry,
)

CUTOFF_AT = datetime(2026, 7, 23, 12, 0, tzinfo=UTC)
RULE_CODES = {
    RuleId.R001: "TRANSFER_ABSOLUTE_HIGH_AMOUNT",
    RuleId.R002: "RECENT_DEVICE_REGISTRATION_HIGH_AMOUNT",
    RuleId.R003: "RECENT_SECURITY_CHANGE_HIGH_AMOUNT",
    RuleId.R004: "RECENT_BENEFICIARY_TRANSFER",
}


def _condition(rule_id: RuleId) -> dict[str, object]:
    match rule_id:
        case RuleId.R001:
            return {
                "transactionTypes": ["ACCOUNT_TRANSFER", "OPEN_BANKING_TRANSFER"],
                "currencyCode": "KRW",
                "amountThreshold": "10000000",
            }
        case RuleId.R002:
            return {
                "prerequisiteRuleCode": "TRANSFER_ABSOLUTE_HIGH_AMOUNT",
                "eventType": "DEVICE_REGISTERED",
                "windowSeconds": 86400,
                "matchPolicy": "SAME_CUSTOMER_AND_DEVICE",
                "selectionPolicy": "LATEST_OCCURRED_AT_THEN_EVENT_ID_ASC",
            }
        case RuleId.R003:
            return {
                "prerequisiteRuleCode": "TRANSFER_ABSOLUTE_HIGH_AMOUNT",
                "passwordEventType": "PASSWORD_CHANGED",
                "transferLimitEventType": "TRANSFER_LIMIT_CHANGED",
                "windowSeconds": 86400,
                "matchPolicy": "SAME_CUSTOMER_AND_SENDER_ACCOUNT",
                "sequencePolicy": "PASSWORD_CHANGED_AT_OR_BEFORE_TRANSFER_LIMIT_CHANGED",
                "selectionPolicy": (
                    "LATEST_TRANSFER_LIMIT_THEN_EVENT_ID_ASC_LATEST_PASSWORD_THEN_EVENT_ID_ASC"
                ),
            }
        case RuleId.R004:
            return {
                "eventType": "BENEFICIARY_REGISTERED",
                "windowSeconds": 86400,
                "matchPolicy": "SAME_CUSTOMER_SENDER_ACCOUNT_AND_BENEFICIARY",
                "selectionPolicy": "LATEST_OCCURRED_AT_THEN_EVENT_ID_ASC",
            }


def _snapshot(rule_id: RuleId, condition_definition: object | None = None) -> RuleVersionSnapshot:
    number = int(rule_id.value[-1])
    return RuleVersionSnapshot(
        fraud_rule_id=UUID(f"10000000-0000-4000-8000-00000000000{number}"),
        rule_code=RULE_CODES[rule_id],
        lifecycle_status=FraudRuleLifecycleStatus.ACTIVE,
        rule_version_id=UUID(f"20000000-0000-4000-8000-00000000000{number}"),
        version_number=1,
        status=RuleVersionStatus.PUBLISHED,
        reason_code=RULE_CODES[rule_id],
        weight={RuleId.R001: 15, RuleId.R002: 20, RuleId.R003: 40, RuleId.R004: 10}[rule_id],
        condition_definition=(
            _condition(rule_id) if condition_definition is None else condition_definition
        ),  # type: ignore[arg-type]
        effective_from=CUTOFF_AT - timedelta(days=1),
        effective_to=CUTOFF_AT + timedelta(days=1),
    )


def _build_with_condition(rule_id: RuleId, condition_definition: object) -> RuleExecutionPlan:
    target = _snapshot(rule_id, condition_definition)
    snapshots = [target]
    if rule_id in {RuleId.R002, RuleId.R003}:
        snapshots.insert(0, _snapshot(RuleId.R001))
    return RuleExecutionPlanBuilder(create_default_rule_evaluator_registry()).build(
        CUTOFF_AT,
        snapshots,
    )


def _assert_unsupported(
    exc_info: pytest.ExceptionInfo[RuleExecutionPlanError],
) -> None:
    assert exc_info.value.category is RuleExecutionPlanErrorCategory.UNSUPPORTED_RULE_CONFIGURATION


def test_all_rule_condition_definitions_are_parsed_to_typed_immutable_values() -> None:
    plan = RuleExecutionPlanBuilder(create_default_rule_evaluator_registry()).build(
        CUTOFF_AT,
        [_snapshot(rule_id) for rule_id in RuleId],
    )

    r001, r002, r003, r004 = (item.condition_definition for item in plan.items)
    assert isinstance(r001, R001ConditionDefinition)
    assert r001.transaction_types == frozenset(
        {TransactionType.ACCOUNT_TRANSFER, TransactionType.OPEN_BANKING_TRANSFER}
    )
    assert r001.amount_threshold == Decimal("10000000")
    assert isinstance(r002, R002ConditionDefinition)
    assert r002.event_type is BehaviorEventType.DEVICE_REGISTERED
    assert isinstance(r003, R003ConditionDefinition)
    assert r003.password_event_type is BehaviorEventType.PASSWORD_CHANGED
    assert r003.transfer_limit_event_type is BehaviorEventType.TRANSFER_LIMIT_CHANGED
    assert isinstance(r004, R004ConditionDefinition)
    assert r004.event_type is BehaviorEventType.BENEFICIARY_REGISTERED


@pytest.mark.parametrize("invalid", (None, [], "{}", 1, True))
def test_condition_definition_rejects_null_and_non_object(invalid: object) -> None:
    snapshot = _snapshot(RuleId.R004)
    snapshot = replace(snapshot, condition_definition=invalid)  # type: ignore[arg-type]

    with pytest.raises(RuleExecutionPlanError) as exc_info:
        RuleExecutionPlanBuilder(create_default_rule_evaluator_registry()).build(
            CUTOFF_AT,
            [snapshot],
        )

    _assert_unsupported(exc_info)


@pytest.mark.parametrize(
    "condition",
    (
        {
            "eventType": "BENEFICIARY_REGISTERED",
            "windowSeconds": 86400,
            "matchPolicy": "SAME_CUSTOMER_SENDER_ACCOUNT_AND_BENEFICIARY",
        },
        {
            "eventType": "BENEFICIARY_REGISTERED",
            "windowSeconds": 86400,
            "matchPolicy": "SAME_CUSTOMER_SENDER_ACCOUNT_AND_BENEFICIARY",
            "selectionPolicy": "LATEST_OCCURRED_AT_THEN_EVENT_ID_ASC",
            "unknownField": "not-allowed",
        },
    ),
)
def test_condition_definition_rejects_missing_and_unknown_fields(
    condition: dict[str, object],
) -> None:
    with pytest.raises(RuleExecutionPlanError) as exc_info:
        _build_with_condition(RuleId.R004, condition)

    _assert_unsupported(exc_info)


@pytest.mark.parametrize(
    ("rule_id", "field", "invalid"),
    (
        (RuleId.R001, "transactionTypes", "ACCOUNT_TRANSFER"),
        (RuleId.R001, "transactionTypes", ["ACCOUNT_TRANSFER", 1]),
        (RuleId.R001, "amountThreshold", 10000000),
        (RuleId.R002, "eventType", ["DEVICE_REGISTERED"]),
        (RuleId.R004, "windowSeconds", "86400"),
    ),
)
def test_condition_definition_rejects_wrong_scalar_and_array_types(
    rule_id: RuleId,
    field: str,
    invalid: object,
) -> None:
    condition = _condition(rule_id)
    condition[field] = invalid

    with pytest.raises(RuleExecutionPlanError) as exc_info:
        _build_with_condition(rule_id, condition)

    _assert_unsupported(exc_info)


@pytest.mark.parametrize("rule_id", (RuleId.R002, RuleId.R003, RuleId.R004))
def test_window_seconds_rejects_bool(rule_id: RuleId) -> None:
    condition = _condition(rule_id)
    condition["windowSeconds"] = True

    with pytest.raises(RuleExecutionPlanError) as exc_info:
        _build_with_condition(rule_id, condition)

    _assert_unsupported(exc_info)


def test_transaction_types_rejects_duplicates() -> None:
    condition = _condition(RuleId.R001)
    condition["transactionTypes"] = ["ACCOUNT_TRANSFER", "ACCOUNT_TRANSFER"]

    with pytest.raises(RuleExecutionPlanError) as exc_info:
        _build_with_condition(RuleId.R001, condition)

    _assert_unsupported(exc_info)


def test_json_key_and_transaction_type_array_order_do_not_change_semantic_plan() -> None:
    first_condition = {
        "transactionTypes": ["ACCOUNT_TRANSFER", "OPEN_BANKING_TRANSFER"],
        "currencyCode": "KRW",
        "amountThreshold": "10000000",
    }
    second_condition = {
        "amountThreshold": "10000000",
        "currencyCode": "KRW",
        "transactionTypes": ["OPEN_BANKING_TRANSFER", "ACCOUNT_TRANSFER"],
    }

    first = _build_with_condition(RuleId.R001, first_condition)
    second = _build_with_condition(RuleId.R001, second_condition)

    assert first == second
    assert first.rule_set_version == second.rule_set_version


@pytest.mark.parametrize(
    ("field", "unsupported"),
    (
        ("transactionTypes", ["ACCOUNT_TRANSFER", "ATM_WITHDRAWAL"]),
        ("currencyCode", "USD"),
        ("amountThreshold", "20000000"),
        ("amountThreshold", "010000000"),
    ),
)
def test_r001_rejects_evaluator_unsupported_high_amount_configuration(
    field: str,
    unsupported: object,
) -> None:
    condition = _condition(RuleId.R001)
    condition[field] = unsupported

    with pytest.raises(RuleExecutionPlanError) as exc_info:
        _build_with_condition(RuleId.R001, condition)

    _assert_unsupported(exc_info)


@pytest.mark.parametrize("rule_id", (RuleId.R002, RuleId.R003, RuleId.R004))
def test_behavior_rules_reject_evaluator_unsupported_window(rule_id: RuleId) -> None:
    condition = _condition(rule_id)
    condition["windowSeconds"] = 3600

    with pytest.raises(RuleExecutionPlanError) as exc_info:
        _build_with_condition(rule_id, condition)

    _assert_unsupported(exc_info)


@pytest.mark.parametrize(
    ("rule_id", "field"),
    (
        (RuleId.R002, "matchPolicy"),
        (RuleId.R002, "selectionPolicy"),
        (RuleId.R003, "sequencePolicy"),
        (RuleId.R003, "selectionPolicy"),
        (RuleId.R004, "matchPolicy"),
        (RuleId.R004, "selectionPolicy"),
    ),
)
def test_behavior_rules_reject_unsupported_policy(rule_id: RuleId, field: str) -> None:
    condition = _condition(rule_id)
    condition[field] = "UNSUPPORTED_POLICY"

    with pytest.raises(RuleExecutionPlanError) as exc_info:
        _build_with_condition(rule_id, condition)

    _assert_unsupported(exc_info)


def test_dependent_rule_plan_rejects_unsupported_shared_r001_configuration() -> None:
    r001 = _snapshot(RuleId.R001)
    r001_condition = dict(r001.condition_definition)
    r001_condition["currencyCode"] = "USD"
    r001 = replace(r001, condition_definition=r001_condition)

    with pytest.raises(RuleExecutionPlanError) as exc_info:
        RuleExecutionPlanBuilder(create_default_rule_evaluator_registry()).build(
            CUTOFF_AT,
            [r001, _snapshot(RuleId.R002)],
        )

    _assert_unsupported(exc_info)
