"""Rule v1 request and response mappings outside the HTTP endpoint."""

from collections.abc import Mapping
from dataclasses import dataclass, fields
from datetime import datetime
from enum import StrEnum
from types import MappingProxyType
from uuid import UUID

from finguardops_ai.rules.v1 import (
    BehaviorEventSnapshot,
    BehaviorEventType,
    FraudRuleLifecycleStatus,
    RuleAnalysisResult,
    RuleEvaluationInput,
    RuleId,
    RuleVersionSnapshot,
    RuleVersionStatus,
    TransactionSnapshot,
    TransactionType,
)
from finguardops_ai.rules.v1.condition_definitions import (
    InvalidConditionDefinitionError,
    parse_condition_definition,
)
from finguardops_ai.schemas.rule_analysis import (
    ExternalRiskMatchRequest,
    ExternalRiskPolicyResult,
    ExternalRiskReasonCode,
    ExternalRiskSubjectType,
    ExternalRiskType,
    R001EvidenceResponse,
    R001ObservationResponse,
    R002EvidenceResponse,
    R002ObservationResponse,
    R003EvidenceResponse,
    R003ObservationResponse,
    R004EvidenceResponse,
    R004ObservationResponse,
    RuleAnalysisRequest,
    RuleAnalysisRequestV2,
    RuleAnalysisResponse,
    RuleAnalysisResultResponse,
    RuleContributionResponse,
    RuleScoreGroupSummaryResponse,
    RuleScoringResultResponse,
    RuleVersionSnapshotRequest,
)


class RuleAnalysisRequestErrorCategory(StrEnum):
    RULE_CONTRACT_ERROR = "RULE_CONTRACT_ERROR"


class RuleAnalysisRequestError(ValueError):
    """A post-DTO Rule v1 request contract failure."""

    def __init__(self, message: str) -> None:
        self.category = RuleAnalysisRequestErrorCategory.RULE_CONTRACT_ERROR
        super().__init__(message)


@dataclass(frozen=True, slots=True)
class RuleAnalysisExecutionInput:
    """Mapped immutable values needed by plan building and Rule execution."""

    evaluation_cutoff_at: datetime
    rule_input: RuleEvaluationInput
    rule_versions: tuple[RuleVersionSnapshot, ...]


_SUPPORTED_TRANSACTION_TYPES = {
    TransactionType.ACCOUNT_TRANSFER,
    TransactionType.OPEN_BANKING_TRANSFER,
}
_RULE_METADATA = MappingProxyType(
    {
        "TRANSFER_ABSOLUTE_HIGH_AMOUNT": (
            RuleId.R001,
            "TRANSFER_ABSOLUTE_HIGH_AMOUNT",
            15,
        ),
        "RECENT_DEVICE_REGISTRATION_HIGH_AMOUNT": (
            RuleId.R002,
            "RECENT_DEVICE_REGISTRATION_HIGH_AMOUNT",
            20,
        ),
        "RECENT_SECURITY_CHANGE_HIGH_AMOUNT": (
            RuleId.R003,
            "RECENT_SECURITY_CHANGE_HIGH_AMOUNT",
            40,
        ),
        "RECENT_BENEFICIARY_TRANSFER": (
            RuleId.R004,
            "RECENT_BENEFICIARY_TRANSFER",
            10,
        ),
    }
)
_SUPPORTED_EXTERNAL_RISK_MATCHES = frozenset(
    {
        (
            ExternalRiskSubjectType.SENDER_ACCOUNT,
            ExternalRiskType.SUSPICIOUS_ACCOUNT,
            ExternalRiskReasonCode.SUSPICIOUS_SENDER_ACCOUNT,
        ),
        (
            ExternalRiskSubjectType.RECIPIENT_ACCOUNT,
            ExternalRiskType.SUSPICIOUS_ACCOUNT,
            ExternalRiskReasonCode.SUSPICIOUS_RECIPIENT_ACCOUNT,
        ),
        (
            ExternalRiskSubjectType.DEVICE,
            ExternalRiskType.RISK_DEVICE,
            ExternalRiskReasonCode.RISK_DEVICE,
        ),
    }
)
_EXTERNAL_RISK_SUBJECT_RANK = MappingProxyType(
    {
        ExternalRiskSubjectType.SENDER_ACCOUNT: 0,
        ExternalRiskSubjectType.RECIPIENT_ACCOUNT: 1,
        ExternalRiskSubjectType.DEVICE: 2,
    }
)
_EXTERNAL_RISK_TYPE_RANK = MappingProxyType(
    {
        ExternalRiskType.SUSPICIOUS_ACCOUNT: 0,
        ExternalRiskType.RISK_DEVICE: 1,
    }
)
_EXTERNAL_RISK_REASON_RANK = MappingProxyType(
    {
        ExternalRiskReasonCode.SUSPICIOUS_SENDER_ACCOUNT: 0,
        ExternalRiskReasonCode.SUSPICIOUS_RECIPIENT_ACCOUNT: 1,
        ExternalRiskReasonCode.RISK_DEVICE: 2,
    }
)


class RuleAnalysisRequestMapper:
    """Map a wire-valid request and enforce non-wire Rule business contracts."""

    @staticmethod
    def to_domain(request: RuleAnalysisRequest) -> RuleAnalysisExecutionInput:
        if not isinstance(request, RuleAnalysisRequest):
            raise TypeError("request must be a RuleAnalysisRequest")

        _validate_request_contract(request)
        transaction = _to_transaction_snapshot(request)
        behavior_events = tuple(
            _to_behavior_event_snapshot(event) for event in request.behavior_events
        )
        rule_versions = tuple(_to_rule_version_snapshot(item) for item in request.rule_versions)
        return RuleAnalysisExecutionInput(
            evaluation_cutoff_at=request.evaluation_cutoff_at,
            rule_input=RuleEvaluationInput(
                transaction=transaction,
                behavior_events=behavior_events,
            ),
            rule_versions=rule_versions,
        )


class RuleAnalysisResponseMapper:
    """Map an existing RuleAnalysisResult without recalculating scoring or Evidence."""

    @staticmethod
    def to_dto(
        transaction_id: UUID,
        trace_id: str,
        analysis_result: RuleAnalysisResult,
    ) -> RuleAnalysisResponse:
        if not isinstance(analysis_result, RuleAnalysisResult):
            raise TypeError("analysis_result must be a RuleAnalysisResult")

        scoring = analysis_result.scoring_result
        scoring_response = RuleScoringResultResponse(
            scoringPolicyVersion=scoring.scoring_policy_version,
            riskScore=scoring.risk_score,
            riskLevel=scoring.risk_level,
            ruleContributions=[
                RuleContributionResponse(
                    ruleId=item.rule_id,
                    executionOrder=item.execution_order,
                    matched=item.matched,
                    originalContribution=item.original_contribution,
                )
                for item in scoring.rule_contributions
            ],
            groupSummaries=[
                RuleScoreGroupSummaryResponse(
                    groupId=item.group_id,
                    rawScore=item.raw_score,
                    cap=item.cap,
                    appliedScore=item.applied_score,
                    reduction=item.reduction,
                )
                for item in scoring.group_summaries
            ],
        )
        analysis_response = RuleAnalysisResultResponse(
            evaluationCutoffAt=_datetime_wire(analysis_result.evaluation_cutoff_at),
            ruleSetVersion=analysis_result.rule_set_version,
            scoringResult=scoring_response,
            evidence=[_to_evidence_response(item) for item in analysis_result.evidence],
        )
        return RuleAnalysisResponse(
            transactionId=str(transaction_id),
            traceId=trace_id,
            analysis=analysis_response,
        )


def _validate_request_contract(request: RuleAnalysisRequest) -> None:
    if request.transaction.transaction_type not in _SUPPORTED_TRANSACTION_TYPES:
        _contract_error("transactionType is not supported by Rule v1")
    if request.evaluation_cutoff_at != request.transaction.occurred_at:
        _contract_error("evaluationCutoffAt must equal transaction.occurredAt")
    if len(request.behavior_events) > 1000:
        _contract_error("behaviorEvents must contain at most 1000 items")
    if not 1 <= len(request.rule_versions) <= 32:
        _contract_error("ruleVersions must contain 1 to 32 items")

    event_ids: set[UUID] = set()
    for event in request.behavior_events:
        if event.event_id in event_ids:
            _contract_error("behaviorEvents must not contain duplicate eventId values")
        event_ids.add(event.event_id)
        _validate_behavior_references(event)

    _validate_rule_versions(request)
    if isinstance(request, RuleAnalysisRequestV2):
        _validate_external_risk_contract(request)


def _validate_external_risk_contract(request: RuleAnalysisRequestV2) -> None:
    external_risk = request.external_risk
    matches = external_risk.matches
    match_count = len(matches)

    if match_count > 3:
        _contract_error("externalRisk.matches must contain at most 3 items")
    if external_risk.policy_result is ExternalRiskPolicyResult.MATCHED and match_count == 0:
        _contract_error("MATCHED externalRisk must contain at least one match")
    if external_risk.policy_result is ExternalRiskPolicyResult.UNMATCHED and match_count != 0:
        _contract_error("UNMATCHED externalRisk must not contain matches")

    seen_matches: set[tuple[ExternalRiskSubjectType, ExternalRiskType, ExternalRiskReasonCode]] = (
        set()
    )
    for match in matches:
        match_key = (match.subject_type, match.external_risk_type, match.reason_code)
        if match_key not in _SUPPORTED_EXTERNAL_RISK_MATCHES:
            _contract_error("externalRisk contains an unsupported match combination")
        if match_key in seen_matches:
            _contract_error("externalRisk must not contain duplicate matches")
        seen_matches.add(match_key)

    canonical_matches = tuple(sorted(matches, key=_external_risk_match_rank))
    if matches != canonical_matches:
        _contract_error("externalRisk.matches must use canonical order")

    if (
        external_risk.provider_as_of > request.evaluation_cutoff_at
        or request.evaluation_cutoff_at > external_risk.looked_up_at
    ):
        _contract_error(
            "externalRisk timestamps must satisfy providerAsOf <= evaluationCutoffAt <= lookedUpAt"
        )


def _external_risk_match_rank(match: ExternalRiskMatchRequest) -> tuple[int, int, int]:
    return (
        _EXTERNAL_RISK_SUBJECT_RANK[match.subject_type],
        _EXTERNAL_RISK_TYPE_RANK[match.external_risk_type],
        _EXTERNAL_RISK_REASON_RANK[match.reason_code],
    )


def _validate_behavior_references(event: object) -> None:
    event_type = event.event_type
    account_ref = event.account_ref
    device_ref = event.device_ref
    beneficiary_ref = event.beneficiary_ref

    if event_type is BehaviorEventType.DEVICE_REGISTERED:
        valid = device_ref is not None and beneficiary_ref is None
    elif event_type is BehaviorEventType.PASSWORD_CHANGED:
        valid = beneficiary_ref is None
    elif event_type is BehaviorEventType.TRANSFER_LIMIT_CHANGED:
        valid = account_ref is not None and beneficiary_ref is None
    else:
        valid = account_ref is not None and beneficiary_ref is not None
    if not valid:
        _contract_error(f"invalid reference combination for eventType {event_type.value}")


def _validate_rule_versions(request: RuleAnalysisRequest) -> None:
    seen_fraud_rule_ids: set[UUID] = set()
    seen_rule_version_ids: set[UUID] = set()
    seen_rule_codes: set[str] = set()
    seen_rule_ids: set[RuleId] = set()

    for item in request.rule_versions:
        if item.version_number < 1:
            _contract_error("versionNumber must be at least 1")
        if not 1 <= item.weight <= 100:
            _contract_error("weight must be between 1 and 100")
        if item.lifecycle_status is not FraudRuleLifecycleStatus.ACTIVE:
            _contract_error("lifecycleStatus must be ACTIVE")
        if item.status is not RuleVersionStatus.PUBLISHED:
            _contract_error("status must be PUBLISHED")
        if item.effective_to is not None and item.effective_to <= item.effective_from:
            _contract_error("effectiveTo must be later than effectiveFrom")
        if item.effective_from > request.evaluation_cutoff_at:
            _contract_error("RuleVersion is not yet effective at evaluationCutoffAt")
        if item.effective_to is not None and request.evaluation_cutoff_at >= item.effective_to:
            _contract_error("RuleVersion is no longer effective at evaluationCutoffAt")

        metadata = _RULE_METADATA.get(item.rule_code)
        if metadata is None:
            _contract_error("ruleCode is not supported by Rule v1")
        rule_id, reason_code, weight = metadata
        if item.reason_code != reason_code or item.weight != weight:
            _contract_error("RuleVersion canonical reasonCode or weight does not match ruleCode")

        _require_unique(item.fraud_rule_id, seen_fraud_rule_ids, "fraudRuleId")
        _require_unique(item.rule_version_id, seen_rule_version_ids, "ruleVersionId")
        _require_unique(item.rule_code, seen_rule_codes, "ruleCode")
        _require_unique(rule_id, seen_rule_ids, "mapped RuleId")

        try:
            parse_condition_definition(rule_id, _copy_json_object(item.condition_definition))
        except InvalidConditionDefinitionError as exc:
            raise RuleAnalysisRequestError(
                f"conditionDefinition is invalid for {item.rule_code}"
            ) from exc

    if RuleId.R002 in seen_rule_ids and RuleId.R001 not in seen_rule_ids:
        _contract_error("R002 requires R001")
    if RuleId.R003 in seen_rule_ids and RuleId.R001 not in seen_rule_ids:
        _contract_error("R003 requires R001")


def _require_unique(value: object, seen: set, field_name: str) -> None:
    if value in seen:
        _contract_error(f"duplicate {field_name}")
    seen.add(value)


def _to_transaction_snapshot(request: RuleAnalysisRequest) -> TransactionSnapshot:
    item = request.transaction
    return TransactionSnapshot(
        transaction_id=item.transaction_id,
        transaction_type=item.transaction_type,
        amount=item.amount,
        currency_code=item.currency_code,
        occurred_at=item.occurred_at,
        external_customer_ref=item.external_customer_ref,
        sender_account_ref=item.sender_account_ref,
        recipient_account_ref=item.recipient_account_ref,
        device_ref=item.device_ref,
    )


def _to_behavior_event_snapshot(event: object) -> BehaviorEventSnapshot:
    return BehaviorEventSnapshot(
        event_id=event.event_id,
        event_type=event.event_type,
        occurred_at=event.occurred_at,
        external_customer_ref=event.external_customer_ref,
        account_ref=event.account_ref,
        device_ref=event.device_ref,
        beneficiary_ref=event.beneficiary_ref,
    )


def _to_rule_version_snapshot(item: RuleVersionSnapshotRequest) -> RuleVersionSnapshot:
    return RuleVersionSnapshot(
        fraud_rule_id=item.fraud_rule_id,
        rule_code=item.rule_code,
        lifecycle_status=item.lifecycle_status,
        rule_version_id=item.rule_version_id,
        version_number=item.version_number,
        status=item.status,
        reason_code=item.reason_code,
        weight=item.weight,
        condition_definition=_copy_json_object(item.condition_definition),
        effective_from=item.effective_from,
        effective_to=item.effective_to,
    )


def _copy_json_value(value: object) -> object:
    if isinstance(value, Mapping):
        return {key: _copy_json_value(item) for key, item in value.items()}
    if isinstance(value, tuple):
        return [_copy_json_value(item) for item in value]
    return value


def _copy_json_object(value: object) -> dict[str, object]:
    copied = _copy_json_value(value)
    if not isinstance(copied, dict):
        raise TypeError("conditionDefinition must be an object")
    return copied


def _to_evidence_response(item: object):
    common = {
        "ruleVersionId": str(item.rule_version_id),
        "ruleCode": item.rule_code,
        "ruleVersion": item.rule_version,
        "reasonCode": item.reason_code,
        "executionOrder": item.execution_order,
        "scoreContribution": item.score_contribution,
        "evidenceOccurredAt": _datetime_wire(item.evidence_occurred_at),
    }
    observation = item.observation_summary
    observation_fields = {field.name for field in fields(observation)}

    if item.rule_id is RuleId.R001:
        _require_observation_fields(observation_fields, {"observed_amount", "amount_threshold"})
        return R001EvidenceResponse(
            ruleId=RuleId.R001,
            observationSummary=R001ObservationResponse(
                observedAmount=str(observation.observed_amount),
                amountThreshold=str(observation.amount_threshold),
            ),
            **common,
        )
    if item.rule_id is RuleId.R002:
        _require_observation_fields(
            observation_fields,
            {
                "observed_amount",
                "amount_threshold",
                "event_id",
                "device_registered_at",
                "elapsed_seconds",
                "window_seconds",
            },
        )
        return R002EvidenceResponse(
            ruleId=RuleId.R002,
            observationSummary=R002ObservationResponse(
                observedAmount=str(observation.observed_amount),
                amountThreshold=str(observation.amount_threshold),
                eventId=str(observation.event_id),
                deviceRegisteredAt=_datetime_wire(observation.device_registered_at),
                elapsedSeconds=observation.elapsed_seconds,
                windowSeconds=observation.window_seconds,
            ),
            **common,
        )
    if item.rule_id is RuleId.R003:
        _require_observation_fields(
            observation_fields,
            {
                "observed_amount",
                "amount_threshold",
                "password_changed_event_id",
                "password_changed_at",
                "transfer_limit_changed_event_id",
                "transfer_limit_changed_at",
                "elapsed_seconds",
                "window_seconds",
            },
        )
        return R003EvidenceResponse(
            ruleId=RuleId.R003,
            observationSummary=R003ObservationResponse(
                observedAmount=str(observation.observed_amount),
                amountThreshold=str(observation.amount_threshold),
                passwordChangedEventId=str(observation.password_changed_event_id),
                passwordChangedAt=_datetime_wire(observation.password_changed_at),
                transferLimitChangedEventId=str(observation.transfer_limit_changed_event_id),
                transferLimitChangedAt=_datetime_wire(observation.transfer_limit_changed_at),
                elapsedSeconds=observation.elapsed_seconds,
                windowSeconds=observation.window_seconds,
            ),
            **common,
        )
    if item.rule_id is RuleId.R004:
        _require_observation_fields(
            observation_fields,
            {
                "observed_amount",
                "event_id",
                "beneficiary_registered_at",
                "elapsed_seconds",
                "window_seconds",
            },
        )
        return R004EvidenceResponse(
            ruleId=RuleId.R004,
            observationSummary=R004ObservationResponse(
                observedAmount=str(observation.observed_amount),
                eventId=str(observation.event_id),
                beneficiaryRegisteredAt=_datetime_wire(observation.beneficiary_registered_at),
                elapsedSeconds=observation.elapsed_seconds,
                windowSeconds=observation.window_seconds,
            ),
            **common,
        )
    raise ValueError(f"unsupported Rule evidence: {item.rule_id!r}")


def _require_observation_fields(actual: set[str], expected: set[str]) -> None:
    if actual != expected:
        raise ValueError("Rule evidence observation fields do not match the response contract")


def _datetime_wire(value: datetime) -> str:
    rendered = value.isoformat(timespec="auto")
    if not rendered.endswith("+00:00"):
        raise ValueError("response datetime must use UTC")
    return f"{rendered[:-6]}Z"


def _contract_error(message: str) -> None:
    raise RuleAnalysisRequestError(message)
