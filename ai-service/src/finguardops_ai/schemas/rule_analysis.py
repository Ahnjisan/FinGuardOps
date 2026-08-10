"""Pydantic wire contracts for the Rule v1 analysis API."""

import re
from collections.abc import Mapping
from datetime import datetime
from decimal import Decimal
from types import MappingProxyType
from typing import Annotated, Literal
from uuid import RFC_4122, UUID

from pydantic import (
    AfterValidator,
    BaseModel,
    BeforeValidator,
    ConfigDict,
    Field,
    PlainSerializer,
    StringConstraints,
)
from pydantic.alias_generators import to_camel

from finguardops_ai.rules.v1 import (
    BehaviorEventType,
    FraudRuleLifecycleStatus,
    RiskLevel,
    RuleId,
    RuleVersionStatus,
    ScoringGroupId,
    TransactionType,
)

_UUID_V4_PATTERN = re.compile(
    r"^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$"
)
_UTC_Z_PATTERN = re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,6})?Z$")
_CANONICAL_DECIMAL_PATTERN = re.compile(r"^[1-9][0-9]*$")


def _parse_uuid_v4(value: object) -> UUID:
    if not isinstance(value, str) or _UUID_V4_PATTERN.fullmatch(value) is None:
        raise ValueError("must be a canonical lowercase UUID v4 string")
    parsed = UUID(value)
    if parsed.version != 4 or parsed.variant != RFC_4122 or str(parsed) != value:
        raise ValueError("must be an RFC 4122 UUID v4")
    return parsed


def _parse_utc_z_datetime(value: object) -> datetime:
    if not isinstance(value, str) or _UTC_Z_PATTERN.fullmatch(value) is None:
        raise ValueError("must be an ISO-8601 UTC Z timestamp with at most 6 fractional digits")
    parsed = datetime.fromisoformat(f"{value[:-1]}+00:00")
    if parsed.utcoffset() is None or parsed.utcoffset().total_seconds() != 0:
        raise ValueError("must use UTC")
    return parsed


def _serialize_utc_z_datetime(value: datetime) -> str:
    rendered = value.isoformat(timespec="auto")
    if not rendered.endswith("+00:00"):
        raise ValueError("datetime must use UTC")
    return f"{rendered[:-6]}Z"


def _parse_canonical_decimal(value: object) -> Decimal:
    if not isinstance(value, str) or _CANONICAL_DECIMAL_PATTERN.fullmatch(value) is None:
        raise ValueError("must be a positive canonical decimal integer string")
    return Decimal(value)


def _serialize_canonical_decimal(value: Decimal) -> str:
    if not value.is_finite() or value <= 0 or value != value.to_integral_value():
        raise ValueError("Decimal must be a positive integer")
    return format(value, "f")


def _freeze_json_value(value: object) -> object:
    if isinstance(value, dict):
        return MappingProxyType({key: _freeze_json_value(item) for key, item in value.items()})
    if isinstance(value, list):
        return tuple(_freeze_json_value(item) for item in value)
    return value


def _freeze_json_object(value: dict[str, object]) -> object:
    return _freeze_json_value(value)


def _thaw_json_value(value: object) -> object:
    if isinstance(value, Mapping):
        return {key: _thaw_json_value(item) for key, item in value.items()}
    if isinstance(value, tuple):
        return [_thaw_json_value(item) for item in value]
    return value


def _serialize_frozen_json_object(value: object) -> dict[str, object]:
    thawed = _thaw_json_value(value)
    if not isinstance(thawed, dict):
        raise TypeError("conditionDefinition must be an object")
    return thawed


CanonicalUuid4 = Annotated[
    UUID,
    BeforeValidator(_parse_uuid_v4),
    PlainSerializer(str, return_type=str),
]
UtcZDateTime = Annotated[
    datetime,
    BeforeValidator(_parse_utc_z_datetime),
    PlainSerializer(_serialize_utc_z_datetime, return_type=str),
]
CanonicalDecimal = Annotated[
    Decimal,
    BeforeValidator(_parse_canonical_decimal),
    PlainSerializer(_serialize_canonical_decimal, return_type=str),
]
ReferenceValue = Annotated[
    str,
    StringConstraints(strict=True, min_length=1, max_length=128, strip_whitespace=False),
    Field(pattern=r"^\S(?:.*\S)?$|^\S$"),
]
CurrencyCode = Annotated[
    str,
    StringConstraints(strict=True, pattern=r"^[A-Z]{3}$"),
]
StrictText = Annotated[str, StringConstraints(strict=True)]
CanonicalRuleVersion = Annotated[
    str,
    StringConstraints(strict=True, pattern=r"^[1-9][0-9]*$"),
]
RuleSetVersion = Annotated[
    str,
    StringConstraints(strict=True, pattern=r"^[0-9a-f]{64}$"),
]
TraceId = Annotated[
    str,
    StringConstraints(strict=True, pattern=r"^[A-Za-z0-9][A-Za-z0-9._:-]{7,63}$"),
]
FrozenJsonObject = Annotated[
    dict[str, object],
    AfterValidator(_freeze_json_object),
    PlainSerializer(_serialize_frozen_json_object, return_type=dict[str, object]),
]


class RuleAnalysisDto(BaseModel):
    """Shared immutable alias and strictness policy for Rule analysis DTOs."""

    model_config = ConfigDict(
        alias_generator=to_camel,
        extra="forbid",
        frozen=True,
        strict=True,
        validate_by_alias=True,
        validate_by_name=False,
        serialize_by_alias=True,
    )


class RuleTransactionSnapshotRequest(RuleAnalysisDto):
    transaction_id: CanonicalUuid4
    transaction_type: TransactionType = Field(strict=False)
    amount: Annotated[CanonicalDecimal, Field(le=Decimal("999999999999999"))]
    currency_code: CurrencyCode
    occurred_at: UtcZDateTime
    external_customer_ref: ReferenceValue
    sender_account_ref: ReferenceValue
    recipient_account_ref: ReferenceValue
    device_ref: ReferenceValue | None


class RuleBehaviorEventSnapshotRequest(RuleAnalysisDto):
    event_id: CanonicalUuid4
    event_type: BehaviorEventType = Field(strict=False)
    occurred_at: UtcZDateTime
    external_customer_ref: ReferenceValue
    account_ref: ReferenceValue | None
    device_ref: ReferenceValue | None
    beneficiary_ref: ReferenceValue | None


class RuleVersionSnapshotRequest(RuleAnalysisDto):
    fraud_rule_id: CanonicalUuid4
    rule_code: StrictText
    lifecycle_status: FraudRuleLifecycleStatus = Field(strict=False)
    rule_version_id: CanonicalUuid4
    version_number: int
    status: RuleVersionStatus = Field(strict=False)
    reason_code: StrictText
    weight: int
    condition_definition: FrozenJsonObject
    effective_from: UtcZDateTime
    effective_to: UtcZDateTime | None


class RuleAnalysisRequest(RuleAnalysisDto):
    evaluation_cutoff_at: UtcZDateTime
    transaction: RuleTransactionSnapshotRequest
    behavior_events: Annotated[
        tuple[RuleBehaviorEventSnapshotRequest, ...],
        Field(strict=False),
    ]
    rule_versions: Annotated[
        tuple[RuleVersionSnapshotRequest, ...],
        Field(strict=False),
    ]


class RuleContributionResponse(RuleAnalysisDto):
    rule_id: RuleId = Field(strict=False)
    execution_order: int
    matched: bool
    original_contribution: int


class RuleScoreGroupSummaryResponse(RuleAnalysisDto):
    group_id: ScoringGroupId = Field(strict=False)
    raw_score: int
    cap: int
    applied_score: int
    reduction: int


class RuleScoringResultResponse(RuleAnalysisDto):
    scoring_policy_version: StrictText
    risk_score: int
    risk_level: RiskLevel = Field(strict=False)
    rule_contributions: Annotated[
        tuple[RuleContributionResponse, ...],
        Field(strict=False),
    ]
    group_summaries: Annotated[
        tuple[RuleScoreGroupSummaryResponse, ...],
        Field(strict=False),
    ]


class R001ObservationResponse(RuleAnalysisDto):
    observed_amount: CanonicalDecimal
    amount_threshold: CanonicalDecimal


class R002ObservationResponse(RuleAnalysisDto):
    observed_amount: CanonicalDecimal
    amount_threshold: CanonicalDecimal
    event_id: CanonicalUuid4
    device_registered_at: UtcZDateTime
    elapsed_seconds: int
    window_seconds: int


class R003ObservationResponse(RuleAnalysisDto):
    observed_amount: CanonicalDecimal
    amount_threshold: CanonicalDecimal
    password_changed_event_id: CanonicalUuid4
    password_changed_at: UtcZDateTime
    transfer_limit_changed_event_id: CanonicalUuid4
    transfer_limit_changed_at: UtcZDateTime
    elapsed_seconds: int
    window_seconds: int


class R004ObservationResponse(RuleAnalysisDto):
    observed_amount: CanonicalDecimal
    event_id: CanonicalUuid4
    beneficiary_registered_at: UtcZDateTime
    elapsed_seconds: int
    window_seconds: int


class _RuleEvidenceResponse(RuleAnalysisDto):
    rule_version_id: CanonicalUuid4
    rule_code: StrictText
    rule_version: CanonicalRuleVersion
    reason_code: StrictText
    execution_order: int
    score_contribution: int
    evidence_occurred_at: UtcZDateTime


class R001EvidenceResponse(_RuleEvidenceResponse):
    rule_id: Literal[RuleId.R001]
    observation_summary: R001ObservationResponse


class R002EvidenceResponse(_RuleEvidenceResponse):
    rule_id: Literal[RuleId.R002]
    observation_summary: R002ObservationResponse


class R003EvidenceResponse(_RuleEvidenceResponse):
    rule_id: Literal[RuleId.R003]
    observation_summary: R003ObservationResponse


class R004EvidenceResponse(_RuleEvidenceResponse):
    rule_id: Literal[RuleId.R004]
    observation_summary: R004ObservationResponse


RuleEvidenceResponse = Annotated[
    R001EvidenceResponse | R002EvidenceResponse | R003EvidenceResponse | R004EvidenceResponse,
    Field(discriminator="rule_id"),
]


class RuleAnalysisResultResponse(RuleAnalysisDto):
    evaluation_cutoff_at: UtcZDateTime
    rule_set_version: RuleSetVersion
    scoring_result: RuleScoringResultResponse
    evidence: Annotated[tuple[RuleEvidenceResponse, ...], Field(strict=False)]


class RuleAnalysisResponse(RuleAnalysisDto):
    transaction_id: CanonicalUuid4
    trace_id: TraceId
    analysis: RuleAnalysisResultResponse
