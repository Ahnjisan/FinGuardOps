from dataclasses import FrozenInstanceError, replace
from datetime import UTC, datetime, timedelta, timezone
from types import MappingProxyType
from uuid import UUID

import pytest

import finguardops_ai.rules.v1.execution_plan as execution_plan_module
from finguardops_ai.rules.v1 import (
    FraudRuleLifecycleStatus,
    R001ConditionDefinition,
    RuleEvaluationInput,
    RuleEvaluationResult,
    RuleExecutionPlanBuilder,
    RuleExecutionPlanError,
    RuleExecutionPlanErrorCategory,
    RuleId,
    RuleVersionSnapshot,
    RuleVersionStatus,
)
from finguardops_ai.rules.v1.registry import (
    RuleEvaluator,
    RuleEvaluatorRegistry,
    RuleEvaluatorResult,
)

CUTOFF_AT = datetime(2026, 7, 23, 12, 0, tzinfo=UTC)
RULE_CODES = {
    RuleId.R001: "TRANSFER_ABSOLUTE_HIGH_AMOUNT",
    RuleId.R002: "RECENT_DEVICE_REGISTRATION_HIGH_AMOUNT",
    RuleId.R003: "RECENT_SECURITY_CHANGE_HIGH_AMOUNT",
    RuleId.R004: "RECENT_BENEFICIARY_TRANSFER",
}
WEIGHTS = {RuleId.R001: 15, RuleId.R002: 20, RuleId.R003: 40, RuleId.R004: 10}


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


def _snapshot(rule_id: RuleId, **overrides: object) -> RuleVersionSnapshot:
    number = int(rule_id.value[-1])
    values: dict[str, object] = {
        "fraud_rule_id": UUID(f"10000000-0000-4000-8000-00000000000{number}"),
        "rule_code": RULE_CODES[rule_id],
        "lifecycle_status": FraudRuleLifecycleStatus.ACTIVE,
        "rule_version_id": UUID(f"20000000-0000-4000-8000-00000000000{number}"),
        "version_number": 1,
        "status": RuleVersionStatus.PUBLISHED,
        "reason_code": RULE_CODES[rule_id],
        "weight": WEIGHTS[rule_id],
        "condition_definition": _condition(rule_id),
        "effective_from": CUTOFF_AT - timedelta(days=1),
        "effective_to": CUTOFF_AT + timedelta(days=1),
    }
    values.update(overrides)
    return RuleVersionSnapshot(**values)  # type: ignore[arg-type]


def _recording_registry(
    rule_ids: tuple[RuleId, ...] = (RuleId.R001, RuleId.R002, RuleId.R003, RuleId.R004),
) -> tuple[RuleEvaluatorRegistry, list[RuleId]]:
    calls: list[RuleId] = []
    registrations: list[tuple[RuleId, RuleEvaluator]] = []
    for registered_rule_id in rule_ids:

        def evaluator(
            _rule_input: RuleEvaluationInput,
            rule_id: RuleId = registered_rule_id,
        ) -> RuleEvaluatorResult:
            calls.append(rule_id)
            return RuleEvaluationResult(rule_id=rule_id, matched=False, facts=None)

        registrations.append((registered_rule_id, evaluator))
    return RuleEvaluatorRegistry(registrations), calls


def _assert_category(
    exc_info: pytest.ExceptionInfo[RuleExecutionPlanError],
    expected: RuleExecutionPlanErrorCategory,
    origin: execution_plan_module.RuleExecutionPlanErrorOrigin = (
        execution_plan_module.RuleExecutionPlanErrorOrigin.REQUEST_CONTRACT
    ),
) -> None:
    assert exc_info.value.category is expected
    assert exc_info.value.origin is origin


def test_builder_creates_r001_r003_r004_plan_in_canonical_order() -> None:
    registry, calls = _recording_registry()
    builder = RuleExecutionPlanBuilder(registry)

    plan = builder.build(
        CUTOFF_AT,
        [_snapshot(RuleId.R004), _snapshot(RuleId.R001), _snapshot(RuleId.R003)],
    )

    assert tuple(item.rule_id for item in plan.items) == (
        RuleId.R001,
        RuleId.R003,
        RuleId.R004,
    )
    assert tuple(item.execution_order for item in plan.items) == (1, 2, 3)
    assert plan.rule_set_version == (
        "085edb92debd4e80d8472f77fab507d846810c668268ee34d8ee97ec2c917b26"
    )
    assert calls == []


def test_builder_allows_r004_as_an_independent_plan() -> None:
    registry, calls = _recording_registry((RuleId.R004,))

    plan = RuleExecutionPlanBuilder(registry).build(CUTOFF_AT, [_snapshot(RuleId.R004)])

    assert len(plan.items) == 1
    assert plan.items[0].rule_id is RuleId.R004
    assert plan.items[0].execution_order == 1
    assert calls == []


def test_input_and_registry_order_do_not_change_plan_or_hash() -> None:
    snapshots = [_snapshot(RuleId.R001), _snapshot(RuleId.R003), _snapshot(RuleId.R004)]
    first_registry, _ = _recording_registry((RuleId.R001, RuleId.R003, RuleId.R004))
    second_registry, _ = _recording_registry((RuleId.R004, RuleId.R003, RuleId.R001))

    first = RuleExecutionPlanBuilder(first_registry).build(CUTOFF_AT, snapshots)
    second = RuleExecutionPlanBuilder(second_registry).build(CUTOFF_AT, list(reversed(snapshots)))

    assert first == second
    assert first.rule_set_version == second.rule_set_version


def test_builder_rejects_empty_snapshot() -> None:
    registry, calls = _recording_registry()

    with pytest.raises(RuleExecutionPlanError) as exc_info:
        RuleExecutionPlanBuilder(registry).build(CUTOFF_AT, [])

    _assert_category(exc_info, RuleExecutionPlanErrorCategory.NO_EXECUTABLE_RULE_VERSION)
    assert calls == []


@pytest.mark.parametrize(
    "overrides",
    (
        {"lifecycle_status": FraudRuleLifecycleStatus.RETIRED},
        {"status": RuleVersionStatus.DRAFT},
        {"effective_from": CUTOFF_AT + timedelta(microseconds=1)},
        {"effective_to": CUTOFF_AT},
    ),
)
def test_builder_rejects_non_executable_snapshot(overrides: dict[str, object]) -> None:
    registry, calls = _recording_registry((RuleId.R004,))

    with pytest.raises(RuleExecutionPlanError) as exc_info:
        RuleExecutionPlanBuilder(registry).build(
            CUTOFF_AT,
            [_snapshot(RuleId.R004, **overrides)],
        )

    _assert_category(exc_info, RuleExecutionPlanErrorCategory.INVALID_RULE_EXECUTION_PLAN)
    assert calls == []


def test_effective_from_is_inclusive_and_effective_to_is_exclusive() -> None:
    registry, _ = _recording_registry((RuleId.R004,))
    builder = RuleExecutionPlanBuilder(registry)

    at_start = builder.build(
        CUTOFF_AT,
        [_snapshot(RuleId.R004, effective_from=CUTOFF_AT, effective_to=None)],
    )

    assert at_start.items[0].effective_from == CUTOFF_AT
    with pytest.raises(RuleExecutionPlanError) as exc_info:
        builder.build(CUTOFF_AT, [_snapshot(RuleId.R004, effective_to=CUTOFF_AT)])
    _assert_category(exc_info, RuleExecutionPlanErrorCategory.INVALID_RULE_EXECUTION_PLAN)


def test_builder_requires_timezone_aware_utc_cutoff() -> None:
    registry, calls = _recording_registry((RuleId.R004,))
    non_utc = CUTOFF_AT.astimezone(timezone(timedelta(hours=9)))

    with pytest.raises(RuleExecutionPlanError) as exc_info:
        RuleExecutionPlanBuilder(registry).build(non_utc, [_snapshot(RuleId.R004)])

    _assert_category(exc_info, RuleExecutionPlanErrorCategory.INVALID_RULE_EXECUTION_PLAN)
    assert calls == []


def test_builder_rejects_duplicate_rule_version_id() -> None:
    registry, calls = _recording_registry()
    first = _snapshot(RuleId.R001)
    duplicate = _snapshot(RuleId.R004, rule_version_id=first.rule_version_id)

    with pytest.raises(RuleExecutionPlanError) as exc_info:
        RuleExecutionPlanBuilder(registry).build(CUTOFF_AT, [first, duplicate])

    _assert_category(exc_info, RuleExecutionPlanErrorCategory.DUPLICATE_RULE_VERSION_ID)
    assert calls == []


def test_builder_rejects_multiple_versions_of_same_fraud_rule_before_duplicate_code() -> None:
    registry, calls = _recording_registry((RuleId.R004,))
    first = _snapshot(RuleId.R004)
    second = replace(
        first,
        rule_version_id=UUID("20000000-0000-4000-8000-000000000014"),
        version_number=2,
    )

    with pytest.raises(RuleExecutionPlanError) as exc_info:
        RuleExecutionPlanBuilder(registry).build(CUTOFF_AT, [first, second])

    _assert_category(
        exc_info,
        RuleExecutionPlanErrorCategory.MULTIPLE_EXECUTABLE_RULE_VERSIONS,
    )
    assert calls == []


def test_builder_rejects_duplicate_rule_code_for_different_fraud_rules() -> None:
    registry, calls = _recording_registry((RuleId.R004,))
    first = _snapshot(RuleId.R004)
    second = replace(
        first,
        fraud_rule_id=UUID("10000000-0000-4000-8000-000000000014"),
        rule_version_id=UUID("20000000-0000-4000-8000-000000000014"),
    )

    with pytest.raises(RuleExecutionPlanError) as exc_info:
        RuleExecutionPlanBuilder(registry).build(CUTOFF_AT, [first, second])

    _assert_category(exc_info, RuleExecutionPlanErrorCategory.DUPLICATE_RULE_CODE)
    assert calls == []


def test_official_bridge_defensively_rejects_duplicate_rule_id_mapping() -> None:
    invalid_bridge = MappingProxyType({"FIRST": RuleId.R001, "SECOND": RuleId.R001})

    with pytest.raises(RuleExecutionPlanError) as exc_info:
        execution_plan_module._validate_rule_code_bridge(invalid_bridge)

    _assert_category(
        exc_info,
        RuleExecutionPlanErrorCategory.DUPLICATE_RULE_ID,
        execution_plan_module.RuleExecutionPlanErrorOrigin.SERVER_CONFIGURATION,
    )


@pytest.mark.parametrize(
    "rule_code",
    (
        "unknown",
        "transfer_absolute_high_amount",
        " R004",
        "R004 ",
    ),
)
def test_builder_rejects_unknown_rule_code_without_normalization(rule_code: str) -> None:
    registry, calls = _recording_registry((RuleId.R004,))

    with pytest.raises(RuleExecutionPlanError) as exc_info:
        RuleExecutionPlanBuilder(registry).build(
            CUTOFF_AT,
            [_snapshot(RuleId.R004, rule_code=rule_code)],
        )

    _assert_category(exc_info, RuleExecutionPlanErrorCategory.UNKNOWN_RULE_CODE)
    assert calls == []


@pytest.mark.parametrize("dependent_rule_id", (RuleId.R002, RuleId.R003))
def test_builder_requires_r001_for_high_amount_dependent_rules(
    dependent_rule_id: RuleId,
) -> None:
    registry, calls = _recording_registry((dependent_rule_id,))

    with pytest.raises(RuleExecutionPlanError) as exc_info:
        RuleExecutionPlanBuilder(registry).build(
            CUTOFF_AT,
            [_snapshot(dependent_rule_id)],
        )

    _assert_category(exc_info, RuleExecutionPlanErrorCategory.MISSING_RULE_DEPENDENCY)
    assert calls == []


def test_builder_translates_registry_lookup_failure_without_running_evaluators() -> None:
    registry, calls = _recording_registry((RuleId.R001,))

    with pytest.raises(RuleExecutionPlanError) as exc_info:
        RuleExecutionPlanBuilder(registry).build(
            CUTOFF_AT,
            [_snapshot(RuleId.R001), _snapshot(RuleId.R004)],
        )

    _assert_category(
        exc_info,
        RuleExecutionPlanErrorCategory.UNSUPPORTED_RULE_CAPABILITY,
        execution_plan_module.RuleExecutionPlanErrorOrigin.DEPLOYED_CAPABILITY,
    )
    assert calls == []


def test_plan_is_deeply_isolated_from_mutable_input() -> None:
    registry, _ = _recording_registry((RuleId.R001,))
    raw_condition = _condition(RuleId.R001)
    input_snapshots = [_snapshot(RuleId.R001, condition_definition=raw_condition)]

    plan = RuleExecutionPlanBuilder(registry).build(CUTOFF_AT, input_snapshots)

    transaction_types = raw_condition["transactionTypes"]
    assert isinstance(transaction_types, list)
    transaction_types.append("ATM_WITHDRAWAL")
    raw_condition["currencyCode"] = "USD"
    input_snapshots.clear()

    parsed = plan.items[0].condition_definition
    assert isinstance(parsed, R001ConditionDefinition)
    assert parsed.transaction_types == frozenset({"ACCOUNT_TRANSFER", "OPEN_BANKING_TRANSFER"})
    assert parsed.currency_code == "KRW"
    assert len(plan.items) == 1


def test_plan_item_and_nested_collections_are_immutable() -> None:
    registry, _ = _recording_registry((RuleId.R001,))
    plan = RuleExecutionPlanBuilder(registry).build(CUTOFF_AT, [_snapshot(RuleId.R001)])
    condition = plan.items[0].condition_definition
    assert isinstance(condition, R001ConditionDefinition)

    with pytest.raises(FrozenInstanceError):
        plan.rule_set_version = "changed"
    with pytest.raises(FrozenInstanceError):
        plan.items[0].weight = 100
    assert isinstance(plan.items, tuple)
    assert isinstance(condition.transaction_types, frozenset)


def test_canonical_input_uses_exact_tabs_lf_trailing_lf_and_utf8_without_bom() -> None:
    registry, _ = _recording_registry()
    plan = RuleExecutionPlanBuilder(registry).build(
        CUTOFF_AT,
        [_snapshot(RuleId.R001), _snapshot(RuleId.R003), _snapshot(RuleId.R004)],
    )

    canonical = execution_plan_module._canonical_rule_set_input(plan.items)

    assert canonical == (
        b"rule-plan-v1\n"
        b"1\t20000000-0000-4000-8000-000000000001\t"
        b"TRANSFER_ABSOLUTE_HIGH_AMOUNT\tR001\t1\n"
        b"2\t20000000-0000-4000-8000-000000000003\t"
        b"RECENT_SECURITY_CHANGE_HIGH_AMOUNT\tR003\t1\n"
        b"3\t20000000-0000-4000-8000-000000000004\t"
        b"RECENT_BENEFICIARY_TRANSFER\tR004\t1\n"
    )
    assert canonical.endswith(b"\n")
    assert b"\r" not in canonical
    assert not canonical.startswith(b"\xef\xbb\xbf")


def test_validation_priority_precedes_later_mapping_and_configuration_errors() -> None:
    registry, calls = _recording_registry()
    duplicate_id = UUID("20000000-0000-4000-8000-000000000099")
    inactive = _snapshot(
        RuleId.R001,
        lifecycle_status=FraudRuleLifecycleStatus.RETIRED,
        rule_version_id=duplicate_id,
        rule_code="UNKNOWN",
        condition_definition=None,
    )
    later = _snapshot(RuleId.R004, rule_version_id=duplicate_id)

    with pytest.raises(RuleExecutionPlanError) as exc_info:
        RuleExecutionPlanBuilder(registry).build(CUTOFF_AT, [inactive, later])

    _assert_category(exc_info, RuleExecutionPlanErrorCategory.INVALID_RULE_EXECUTION_PLAN)
    assert calls == []


def test_reason_code_requires_only_a_non_empty_string_in_this_layer() -> None:
    registry, _ = _recording_registry((RuleId.R004,))
    builder = RuleExecutionPlanBuilder(registry)

    plan = builder.build(
        CUTOFF_AT,
        [_snapshot(RuleId.R004, reason_code="A_DIFFERENT_NON_EMPTY_REASON")],
    )

    assert plan.items[0].reason_code == "A_DIFFERENT_NON_EMPTY_REASON"
    with pytest.raises(RuleExecutionPlanError) as exc_info:
        builder.build(CUTOFF_AT, [_snapshot(RuleId.R004, reason_code="")])
    _assert_category(exc_info, RuleExecutionPlanErrorCategory.INVALID_RULE_EXECUTION_PLAN)
