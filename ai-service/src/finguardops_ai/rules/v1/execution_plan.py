import hashlib
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from datetime import datetime, timedelta
from enum import StrEnum
from types import MappingProxyType
from uuid import RFC_4122, UUID

from finguardops_ai.rules.v1.condition_definitions import (
    InvalidConditionDefinitionError,
    RuleConditionDefinition,
    parse_condition_definition,
)
from finguardops_ai.rules.v1.models import RuleId
from finguardops_ai.rules.v1.registry import RuleEvaluatorRegistry, UnsupportedRuleIdError


class FraudRuleLifecycleStatus(StrEnum):
    ACTIVE = "ACTIVE"
    RETIRED = "RETIRED"


class RuleVersionStatus(StrEnum):
    DRAFT = "DRAFT"
    PUBLISHED = "PUBLISHED"
    WITHDRAWN = "WITHDRAWN"


class RuleExecutionPlanErrorCategory(StrEnum):
    NO_EXECUTABLE_RULE_VERSION = "NO_EXECUTABLE_RULE_VERSION"
    MULTIPLE_EXECUTABLE_RULE_VERSIONS = "MULTIPLE_EXECUTABLE_RULE_VERSIONS"
    UNKNOWN_RULE_CODE = "UNKNOWN_RULE_CODE"
    DUPLICATE_RULE_VERSION_ID = "DUPLICATE_RULE_VERSION_ID"
    DUPLICATE_RULE_CODE = "DUPLICATE_RULE_CODE"
    DUPLICATE_RULE_ID = "DUPLICATE_RULE_ID"
    MISSING_RULE_DEPENDENCY = "MISSING_RULE_DEPENDENCY"
    UNSUPPORTED_RULE_CONFIGURATION = "UNSUPPORTED_RULE_CONFIGURATION"
    UNSUPPORTED_RULE_CAPABILITY = "UNSUPPORTED_RULE_CAPABILITY"
    INVALID_RULE_EXECUTION_PLAN = "INVALID_RULE_EXECUTION_PLAN"


class RuleExecutionPlanError(ValueError):
    """A categorized failure to build an immutable Rule execution plan."""

    def __init__(self, category: RuleExecutionPlanErrorCategory, message: str) -> None:
        self.category = category
        super().__init__(message)


@dataclass(frozen=True, slots=True)
class RuleVersionSnapshot:
    """RuleVersion business values fixed by the upstream owner for one evaluation."""

    fraud_rule_id: UUID
    rule_code: str
    lifecycle_status: FraudRuleLifecycleStatus
    rule_version_id: UUID
    version_number: int
    status: RuleVersionStatus
    reason_code: str
    weight: int
    condition_definition: Mapping[str, object]
    effective_from: datetime
    effective_to: datetime | None


@dataclass(frozen=True, slots=True)
class RuleExecutionPlanItem:
    rule_version_id: UUID
    rule_code: str
    rule_id: RuleId
    version_number: int
    reason_code: str
    weight: int
    condition_definition: RuleConditionDefinition
    effective_from: datetime
    effective_to: datetime | None
    execution_order: int


@dataclass(frozen=True, slots=True)
class RuleExecutionPlan:
    evaluation_cutoff_at: datetime
    rule_set_version: str
    items: tuple[RuleExecutionPlanItem, ...]


_RULE_CODE_TO_RULE_ID: Mapping[str, RuleId] = MappingProxyType(
    {
        "TRANSFER_ABSOLUTE_HIGH_AMOUNT": RuleId.R001,
        "RECENT_DEVICE_REGISTRATION_HIGH_AMOUNT": RuleId.R002,
        "RECENT_SECURITY_CHANGE_HIGH_AMOUNT": RuleId.R003,
        "RECENT_BENEFICIARY_TRANSFER": RuleId.R004,
    }
)
_CANONICAL_RULE_ORDER = (RuleId.R001, RuleId.R002, RuleId.R003, RuleId.R004)
_CANONICAL_RULE_INDEX = MappingProxyType(
    {rule_id: index for index, rule_id in enumerate(_CANONICAL_RULE_ORDER)}
)


@dataclass(frozen=True, slots=True)
class _MappedRuleVersion:
    snapshot: RuleVersionSnapshot
    rule_id: RuleId


@dataclass(frozen=True, slots=True)
class _ParsedRuleVersion:
    snapshot: RuleVersionSnapshot
    rule_id: RuleId
    condition_definition: RuleConditionDefinition


@dataclass(frozen=True, slots=True)
class RuleExecutionPlanBuilder:
    """Build a plan without invoking the Orchestrator or any evaluator.

    The caller owns verification that evaluation_cutoff_at equals the transaction's
    occurred_at; this pure builder has no transaction snapshot.
    """

    registry: RuleEvaluatorRegistry

    def build(
        self,
        evaluation_cutoff_at: datetime,
        rule_versions: Sequence[RuleVersionSnapshot],
    ) -> RuleExecutionPlan:
        _require_utc_datetime(evaluation_cutoff_at, "evaluation_cutoff_at")
        rule_versions_snapshot = _snapshot_rule_versions(rule_versions)

        if not rule_versions_snapshot:
            _raise_plan_error(
                RuleExecutionPlanErrorCategory.NO_EXECUTABLE_RULE_VERSION,
                "rule_versions must contain at least one executable RuleVersion",
            )

        _validate_executable_rule_versions(rule_versions_snapshot, evaluation_cutoff_at)
        _validate_unique_rule_version_ids(rule_versions_snapshot)
        _validate_rule_identity_uniqueness(rule_versions_snapshot)
        mapped_rule_versions = _map_rule_ids(rule_versions_snapshot)
        _validate_rule_code_bridge(_RULE_CODE_TO_RULE_ID)
        _validate_unique_mapped_rule_ids(mapped_rule_versions)
        _validate_dependencies(mapped_rule_versions)
        parsed_rule_versions = _parse_condition_definitions(mapped_rule_versions)
        items = _create_canonical_items(parsed_rule_versions)
        plan = RuleExecutionPlan(
            evaluation_cutoff_at=evaluation_cutoff_at,
            rule_set_version=_create_rule_set_version(items),
            items=items,
        )
        _validate_registry_capabilities(self.registry, plan.items)
        return plan


def _snapshot_rule_versions(
    rule_versions: Sequence[RuleVersionSnapshot],
) -> tuple[RuleVersionSnapshot, ...]:
    if isinstance(rule_versions, (str, bytes)) or not isinstance(rule_versions, Sequence):
        _raise_plan_error(
            RuleExecutionPlanErrorCategory.INVALID_RULE_EXECUTION_PLAN,
            "rule_versions must be a non-string Sequence",
        )
    snapshot = tuple(rule_versions)
    for index, rule_version in enumerate(snapshot):
        if not isinstance(rule_version, RuleVersionSnapshot):
            _raise_plan_error(
                RuleExecutionPlanErrorCategory.INVALID_RULE_EXECUTION_PLAN,
                f"rule_versions[{index}] must be a RuleVersionSnapshot",
            )
        _validate_snapshot_structure(rule_version, index)
    return snapshot


def _validate_snapshot_structure(rule_version: RuleVersionSnapshot, index: int) -> None:
    prefix = f"rule_versions[{index}]"
    _require_uuid_v4(rule_version.fraud_rule_id, f"{prefix}.fraud_rule_id")
    if not isinstance(rule_version.rule_code, str):
        _invalid_plan(f"{prefix}.rule_code must be a string")
    if not isinstance(rule_version.lifecycle_status, FraudRuleLifecycleStatus):
        _invalid_plan(f"{prefix}.lifecycle_status must be a FraudRuleLifecycleStatus")
    _require_uuid_v4(rule_version.rule_version_id, f"{prefix}.rule_version_id")
    _require_positive_integer(rule_version.version_number, f"{prefix}.version_number")
    if not isinstance(rule_version.status, RuleVersionStatus):
        _invalid_plan(f"{prefix}.status must be a RuleVersionStatus")
    if not isinstance(rule_version.reason_code, str) or not rule_version.reason_code:
        _invalid_plan(f"{prefix}.reason_code must be a non-empty string")
    _require_bounded_integer(rule_version.weight, f"{prefix}.weight", 1, 100)
    _require_utc_datetime(rule_version.effective_from, f"{prefix}.effective_from")
    if rule_version.effective_to is not None:
        _require_utc_datetime(rule_version.effective_to, f"{prefix}.effective_to")


def _validate_executable_rule_versions(
    rule_versions: tuple[RuleVersionSnapshot, ...],
    evaluation_cutoff_at: datetime,
) -> None:
    for rule_version in rule_versions:
        if rule_version.lifecycle_status is not FraudRuleLifecycleStatus.ACTIVE:
            _invalid_plan(f"Rule {rule_version.rule_code!r} lifecycle is not ACTIVE")
        if rule_version.status is not RuleVersionStatus.PUBLISHED:
            _invalid_plan(f"RuleVersion {rule_version.rule_version_id} is not PUBLISHED")
        if (
            rule_version.effective_to is not None
            and rule_version.effective_to <= rule_version.effective_from
        ):
            _invalid_plan("effective_to must be later than effective_from")
        if rule_version.effective_from > evaluation_cutoff_at:
            _invalid_plan(f"RuleVersion {rule_version.rule_version_id} is not yet effective")
        if (
            rule_version.effective_to is not None
            and evaluation_cutoff_at >= rule_version.effective_to
        ):
            _invalid_plan(f"RuleVersion {rule_version.rule_version_id} is no longer effective")


def _validate_unique_rule_version_ids(
    rule_versions: tuple[RuleVersionSnapshot, ...],
) -> None:
    seen: set[UUID] = set()
    for rule_version in rule_versions:
        if rule_version.rule_version_id in seen:
            _raise_plan_error(
                RuleExecutionPlanErrorCategory.DUPLICATE_RULE_VERSION_ID,
                f"Duplicate rule_version_id: {rule_version.rule_version_id}",
            )
        seen.add(rule_version.rule_version_id)


def _validate_rule_identity_uniqueness(
    rule_versions: tuple[RuleVersionSnapshot, ...],
) -> None:
    seen_fraud_rule_ids: set[UUID] = set()
    for rule_version in rule_versions:
        if rule_version.fraud_rule_id in seen_fraud_rule_ids:
            _raise_plan_error(
                RuleExecutionPlanErrorCategory.MULTIPLE_EXECUTABLE_RULE_VERSIONS,
                f"Multiple executable versions for fraud_rule_id {rule_version.fraud_rule_id}",
            )
        seen_fraud_rule_ids.add(rule_version.fraud_rule_id)

    seen_rule_codes: set[str] = set()
    for rule_version in rule_versions:
        if rule_version.rule_code in seen_rule_codes:
            _raise_plan_error(
                RuleExecutionPlanErrorCategory.DUPLICATE_RULE_CODE,
                f"Duplicate rule_code: {rule_version.rule_code!r}",
            )
        seen_rule_codes.add(rule_version.rule_code)


def _map_rule_ids(
    rule_versions: tuple[RuleVersionSnapshot, ...],
) -> tuple[_MappedRuleVersion, ...]:
    mapped: list[_MappedRuleVersion] = []
    for rule_version in rule_versions:
        try:
            rule_id = _RULE_CODE_TO_RULE_ID[rule_version.rule_code]
        except KeyError:
            _raise_plan_error(
                RuleExecutionPlanErrorCategory.UNKNOWN_RULE_CODE,
                f"Unknown rule_code: {rule_version.rule_code!r}",
            )
        mapped.append(_MappedRuleVersion(snapshot=rule_version, rule_id=rule_id))
    return tuple(mapped)


def _validate_rule_code_bridge(bridge: Mapping[str, RuleId]) -> None:
    seen_rule_ids: set[RuleId] = set()
    for rule_code, rule_id in bridge.items():
        if not isinstance(rule_code, str) or not isinstance(rule_id, RuleId):
            _invalid_plan("Rule code bridge contains an invalid entry")
        if rule_id in seen_rule_ids:
            _raise_plan_error(
                RuleExecutionPlanErrorCategory.DUPLICATE_RULE_ID,
                f"Rule code bridge maps multiple rule codes to {rule_id}",
            )
        seen_rule_ids.add(rule_id)


def _validate_unique_mapped_rule_ids(
    mapped_rule_versions: tuple[_MappedRuleVersion, ...],
) -> None:
    seen: set[RuleId] = set()
    for mapped in mapped_rule_versions:
        if mapped.rule_id in seen:
            _raise_plan_error(
                RuleExecutionPlanErrorCategory.DUPLICATE_RULE_ID,
                f"Duplicate mapped Rule ID: {mapped.rule_id}",
            )
        seen.add(mapped.rule_id)


def _validate_dependencies(mapped_rule_versions: tuple[_MappedRuleVersion, ...]) -> None:
    rule_ids = {mapped.rule_id for mapped in mapped_rule_versions}
    if RuleId.R001 not in rule_ids and ({RuleId.R002, RuleId.R003} & rule_ids):
        _raise_plan_error(
            RuleExecutionPlanErrorCategory.MISSING_RULE_DEPENDENCY,
            "R002 and R003 require R001 in the same execution plan",
        )


def _parse_condition_definitions(
    mapped_rule_versions: tuple[_MappedRuleVersion, ...],
) -> tuple[_ParsedRuleVersion, ...]:
    parsed: list[_ParsedRuleVersion] = []
    for mapped in mapped_rule_versions:
        try:
            condition_definition = parse_condition_definition(
                mapped.rule_id,
                mapped.snapshot.condition_definition,
            )
        except InvalidConditionDefinitionError as exc:
            raise RuleExecutionPlanError(
                RuleExecutionPlanErrorCategory.UNSUPPORTED_RULE_CONFIGURATION,
                f"Unsupported configuration for {mapped.snapshot.rule_code}: {exc}",
            ) from exc
        parsed.append(
            _ParsedRuleVersion(
                snapshot=mapped.snapshot,
                rule_id=mapped.rule_id,
                condition_definition=condition_definition,
            )
        )
    return tuple(parsed)


def _create_canonical_items(
    parsed_rule_versions: tuple[_ParsedRuleVersion, ...],
) -> tuple[RuleExecutionPlanItem, ...]:
    ordered = sorted(parsed_rule_versions, key=lambda parsed: _CANONICAL_RULE_INDEX[parsed.rule_id])
    return tuple(
        RuleExecutionPlanItem(
            rule_version_id=parsed.snapshot.rule_version_id,
            rule_code=parsed.snapshot.rule_code,
            rule_id=parsed.rule_id,
            version_number=parsed.snapshot.version_number,
            reason_code=parsed.snapshot.reason_code,
            weight=parsed.snapshot.weight,
            condition_definition=parsed.condition_definition,
            effective_from=parsed.snapshot.effective_from,
            effective_to=parsed.snapshot.effective_to,
            execution_order=execution_order,
        )
        for execution_order, parsed in enumerate(ordered, start=1)
    )


def _canonical_rule_set_input(items: tuple[RuleExecutionPlanItem, ...]) -> bytes:
    lines = ["rule-plan-v1"]
    lines.extend(
        "\t".join(
            (
                str(item.execution_order),
                str(item.rule_version_id),
                item.rule_code,
                item.rule_id.value,
                str(item.version_number),
            )
        )
        for item in items
    )
    return ("\n".join(lines) + "\n").encode("utf-8")


def _create_rule_set_version(items: tuple[RuleExecutionPlanItem, ...]) -> str:
    return hashlib.sha256(_canonical_rule_set_input(items)).hexdigest()


def _validate_registry_capabilities(
    registry: RuleEvaluatorRegistry,
    items: tuple[RuleExecutionPlanItem, ...],
) -> None:
    for item in items:
        try:
            registry.get_evaluator(item.rule_id)
        except UnsupportedRuleIdError as exc:
            raise RuleExecutionPlanError(
                RuleExecutionPlanErrorCategory.UNSUPPORTED_RULE_CAPABILITY,
                f"Registry does not support Rule ID {item.rule_id}",
            ) from exc


def _require_uuid_v4(value: object, field_name: str) -> None:
    if not isinstance(value, UUID):
        _invalid_plan(f"{field_name} must be a UUID")
    if value.version != 4 or value.variant != RFC_4122:
        _invalid_plan(f"{field_name} must be an RFC 4122 UUID v4")


def _require_utc_datetime(value: object, field_name: str) -> None:
    if not isinstance(value, datetime):
        _invalid_plan(f"{field_name} must be a datetime")
    if value.tzinfo is None or value.utcoffset() is None:
        _invalid_plan(f"{field_name} must be timezone-aware UTC")
    if value.utcoffset() != timedelta(0):
        _invalid_plan(f"{field_name} must use UTC")


def _require_positive_integer(value: object, field_name: str) -> None:
    if type(value) is not int or value < 1:
        _invalid_plan(f"{field_name} must be a positive integer")


def _require_bounded_integer(value: object, field_name: str, minimum: int, maximum: int) -> None:
    if type(value) is not int or not minimum <= value <= maximum:
        _invalid_plan(f"{field_name} must be an integer from {minimum} to {maximum}")


def _invalid_plan(message: str) -> None:
    _raise_plan_error(RuleExecutionPlanErrorCategory.INVALID_RULE_EXECUTION_PLAN, message)


def _raise_plan_error(category: RuleExecutionPlanErrorCategory, message: str) -> None:
    raise RuleExecutionPlanError(category, message)
