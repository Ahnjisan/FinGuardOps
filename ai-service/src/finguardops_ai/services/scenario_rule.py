"""Pure, version-pinned SCN-001/002 Rule scoring for local synthetic inputs."""

import hashlib
from datetime import datetime, timedelta
from decimal import Decimal

from finguardops_ai.mappers.rule_analysis import RuleAnalysisRequestError
from finguardops_ai.ml.features_v2 import extract_features
from finguardops_ai.schemas.scenario_rule import (
    RuleAnalysisRequestV3,
    RuleAnalysisResponseV3,
)

_CODES = (
    "TRANSFER_ABSOLUTE_HIGH_AMOUNT",
    "RECENT_DEVICE_REGISTRATION_HIGH_AMOUNT",
    "RECENT_SECURITY_CHANGE_HIGH_AMOUNT",
    "RECENT_BENEFICIARY_TRANSFER",
    "EXTERNAL_SUSPICIOUS_RECIPIENT_ACCOUNT",
    "CUSTOMER_BASELINE_HIGH_AMOUNT",
    "EXTERNAL_RISK_DEVICE_MATCH",
    "MOCK_APP_SECURITY_ALERT",
    "MATERIAL_LIMIT_UPLIFT",
    "MOCK_OVERSEAS_NETWORK",
)
_VERSIONS = (3, 4, 4, 4, 1, 1, 1, 1, 1, 1)
_WEIGHTS = (15, 20, 50, 10, 40, 20, 20, 15, 10, 10)
_GROUPS = (
    "amount",
    "security",
    "security",
    "beneficiary",
    "external_recipient",
    "amount",
    "security",
    "security",
    "security",
    "security",
)
_CAPS = (("amount", 20), ("security", 60), ("beneficiary", 10), ("external_recipient", 40))
_SIGNALS = {
    1: "NEW_UNVERIFIED_DEVICE",
    2: "PASSWORD_LIMIT_SEQUENCE",
    3: "FIRST_REGISTERED_RECIPIENT",
    5: "TENFOLD_BASELINE",
    6: "EXACT_RISK_DEVICE",
    7: "APP_SECURITY_ALERT",
    8: "MATERIAL_LIMIT_UPLIFT",
    9: "OVERSEAS_NETWORK",
}


def analyze(request: RuleAnalysisRequestV3, trace_id: str) -> RuleAnalysisResponseV3:
    _validate(request)
    t = request.evaluation_cutoff_at
    f = request.scenario_facts
    amount = request.transaction.amount
    transfer = request.transaction.transaction_type in {"ACCOUNT_TRANSFER", "OPEN_BANKING_TRANSFER"}
    r001 = transfer and amount >= Decimal("10000000")
    r006 = (
        f.baseline_status == "AVAILABLE"
        and f.baseline_count >= 10
        and f.baseline_days >= 3
        and f.baseline_total > 0
        and amount >= Decimal("1000000")
        and amount * f.baseline_count >= f.baseline_total * 10
    )
    r002 = f.device_status == "NEW_UNVERIFIED" and f.device_event_id is not None and (r001 or r006)
    r003 = (
        f.limit_status == "RAISED"
        and f.password_event_id is not None
        and f.limit_event_id is not None
        and _within_strict(f.password_changed_at, f.limit_changed_at, 30)
        and _within_strict(f.limit_changed_at, t, 30)
        and _within_strict(f.password_changed_at, t, 60)
        and (r001 or r006 or f.prior_limit_exceeded == "TRUE")
    )
    r004 = f.recipient_status == "FIRST" and f.beneficiary_event_id is not None
    r005 = any(
        m.subject_type == "RECIPIENT_ACCOUNT"
        and m.external_risk_type == "SUSPICIOUS_ACCOUNT"
        and m.reason_code == "SUSPICIOUS_RECIPIENT_ACCOUNT"
        for m in request.external_risk.matches
    )
    r007 = f.exact_provider_device_match
    r008 = f.app_security_status == "ALERT" and _in_window(f.app_security_at, t, 24 * 60)
    r009 = (
        r003
        and f.previous_limit is not None
        and f.new_limit is not None
        and f.new_limit >= f.previous_limit * 2
        and f.new_limit - f.previous_limit >= Decimal("5000000")
    )
    r010 = f.network_region_status == "OVERSEAS" and _in_window(f.network_region_at, t, 15)
    matched = (
        r001,
        r002 and transfer,
        r003 and transfer,
        r004 and transfer,
        r005 and transfer,
        r006 and transfer,
        r007 and transfer,
        r008 and transfer,
        r009 and transfer,
        r010 and transfer,
    )

    raw = {group: 0 for group, _ in _CAPS}
    applied = {group: 0 for group, _ in _CAPS}
    cap_by_group = dict(_CAPS)
    contributions = []
    evidence = []
    accepted_total = 0
    for index, hit in enumerate(matched):
        group = _GROUPS[index]
        original = _WEIGHTS[index] if hit else 0
        accepted = min(original, cap_by_group[group] - applied[group], 100 - accepted_total)
        raw[group] += original
        applied[group] += accepted
        accepted_total += accepted
        contributions.append(
            {
                "ruleId": f"R{index + 1:03d}",
                "executionOrder": index + 1,
                "matched": hit,
                "originalContribution": original,
                "appliedContribution": accepted,
            }
        )
        if hit:
            rule = request.rule_versions[index]
            observation, occurred_at = _observation(index, request)
            evidence.append(
                {
                    "ruleId": f"R{index + 1:03d}",
                    "ruleVersionId": str(rule.rule_version_id),
                    "ruleCode": rule.rule_code,
                    "ruleVersion": str(rule.version_number),
                    "reasonCode": rule.reason_code,
                    "executionOrder": index + 1,
                    "scoreContribution": original,
                    "appliedContribution": accepted,
                    "observationSummary": observation,
                    "evidenceOccurredAt": _wire(occurred_at),
                }
            )
    summaries = [
        {
            "groupId": group,
            "rawScore": raw[group],
            "cap": cap,
            "appliedScore": applied[group],
            "reduction": raw[group] - applied[group],
        }
        for group, cap in _CAPS
    ]
    score = sum(applied.values())
    if score != accepted_total:
        raise RuntimeError("Rule v4 attribution mismatch")
    level = (
        "LOW" if score < 20 else "MEDIUM" if score < 50 else "HIGH" if score < 80 else "CRITICAL"
    )
    return RuleAnalysisResponseV3.model_validate(
        {
            "transactionId": str(request.transaction.transaction_id),
            "traceId": trace_id,
            "analysis": {
                "evaluationCutoffAt": _wire(t),
                "ruleSetVersion": _rule_set_version(request),
                "scoringResult": {
                    "scoringPolicyVersion": "scoring-policy-v4",
                    "riskScore": score,
                    "riskLevel": level,
                    "ruleContributions": tuple(contributions),
                    "groupSummaries": tuple(summaries),
                },
                "evidence": tuple(evidence),
            },
        }
    )


def _validate(request: RuleAnalysisRequestV3) -> None:
    t = request.evaluation_cutoff_at
    f = request.scenario_facts
    if request.transaction.currency_code != "KRW":
        raise RuleAnalysisRequestError("Rule v4 requires KRW")
    transfer = request.transaction.transaction_type in {"ACCOUNT_TRANSFER", "OPEN_BANKING_TRANSFER"}
    if transfer != (request.transaction.recipient_account_ref is not None):
        raise RuleAnalysisRequestError("Transaction recipient context mismatch")
    if request.transaction.occurred_at != t or len(request.rule_versions) != 10:
        raise RuleAnalysisRequestError("Invalid Rule v4 cutoff or set length")
    if f.source_version != "SCN001-002-contract-v1" or f.baseline_count < 0 or f.baseline_days < 0:
        raise RuleAnalysisRequestError("Invalid scenario facts")
    try:
        channel = {
            "ACCOUNT_TRANSFER": "MOBILE_BANKING",
            "OPEN_BANKING_TRANSFER": "OPEN_BANKING",
            "ATM_WITHDRAWAL": "ATM",
            "LOAN_DISBURSED": "CORE_BANKING",
        }[request.transaction.transaction_type]
        extract_features(
            amount=request.transaction.amount,
            channel=channel,
            cutoff=t,
            facts=f,
            transfer=request.transaction.transaction_type
            in {"ACCOUNT_TRANSFER", "OPEN_BANKING_TRANSFER"},
        )
    except ValueError as error:
        raise RuleAnalysisRequestError("Invalid cutoff-fixed scenario facts") from error
    if f.baseline_status not in {"AVAILABLE", "INSUFFICIENT", "UNKNOWN"}:
        raise RuleAnalysisRequestError("Invalid baseline state")
    if f.recipient_status not in {"FIRST", "KNOWN", "UNKNOWN"}:
        raise RuleAnalysisRequestError("Invalid recipient state")
    if f.device_status not in {"NEW_UNVERIFIED", "NEW_VERIFIED", "KNOWN", "UNKNOWN"}:
        raise RuleAnalysisRequestError("Invalid device state")
    if f.limit_status not in {"RAISED", "NOT_RAISED", "UNKNOWN"}:
        raise RuleAnalysisRequestError("Invalid limit state")
    if f.prior_limit_exceeded not in {"TRUE", "FALSE", "UNKNOWN"}:
        raise RuleAnalysisRequestError("Invalid daily/per-transfer state")
    if (
        f.baseline_status == "AVAILABLE"
        and f.transfer_coverage_fact_id is None
        or f.recipient_status == "FIRST"
        and f.transfer_coverage_fact_id is None
        or f.device_status in {"NEW_UNVERIFIED", "NEW_VERIFIED"}
        and (f.device_coverage_fact_id is None or f.device_trust_fact_id is None)
        or f.limit_status == "RAISED"
        and f.limit_fact_id is None
        or f.app_security_status == "ALERT"
        and f.app_security_fact_id is None
        or f.network_region_status == "OVERSEAS"
        and f.network_region_fact_id is None
    ):
        raise RuleAnalysisRequestError("Missing trusted Mock provenance")
    if f.baseline_status == "AVAILABLE" and (
        f.baseline_count < 10 or f.baseline_days < 3 or f.baseline_total <= 0
    ):
        raise RuleAnalysisRequestError("Invalid available baseline")
    if f.limit_status == "RAISED" and (
        f.previous_limit is None
        or f.new_limit is None
        or f.previous_limit <= 0
        or f.new_limit <= f.previous_limit
    ):
        raise RuleAnalysisRequestError("Invalid raised limit")
    if request.external_risk.provider_as_of > t or request.external_risk.looked_up_at < t:
        raise RuleAnalysisRequestError("External Risk time mismatch")
    device_candidate = any(
        m.subject_type == "DEVICE"
        and m.external_risk_type == "RISK_DEVICE"
        and m.reason_code == "RISK_DEVICE"
        for m in request.external_risk.matches
    )
    if (
        request.transaction.transaction_type in {"ACCOUNT_TRANSFER", "OPEN_BANKING_TRANSFER"}
        and (request.transaction.recipient_account_ref is not None or device_candidate)
    ) and (request.external_risk.provider_as_of < t - timedelta(hours=24)):
        raise RuleAnalysisRequestError("External Risk stale")
    if f.exact_provider_device_match and (
        request.transaction.device_ref is None
        or not any(
            m.subject_type == "DEVICE"
            and m.external_risk_type == "RISK_DEVICE"
            and m.reason_code == "RISK_DEVICE"
            for m in request.external_risk.matches
        )
    ):
        raise RuleAnalysisRequestError("Unbound device signal")
    seen_ids = set()
    for index, rule in enumerate(request.rule_versions):
        if (
            rule.rule_code != _CODES[index]
            or rule.version_number != _VERSIONS[index]
            or rule.reason_code != _CODES[index]
            or rule.weight != _WEIGHTS[index]
            or rule.rule_version_id in seen_ids
            or rule.effective_from > t
            or rule.effective_to is not None
            and t >= rule.effective_to
        ):
            raise RuleAnalysisRequestError("Rule v4 binding mismatch")
        seen_ids.add(rule.rule_version_id)
        if index == 0 and rule.condition_definition != {
            "transactionTypes": ("ACCOUNT_TRANSFER", "OPEN_BANKING_TRANSFER"),
            "currencyCode": "KRW",
            "amountThreshold": "10000000",
        }:
            raise RuleAnalysisRequestError("R001 v4 binding mismatch")
        if index == 4 and rule.condition_definition != {
            "subjectType": "RECIPIENT_ACCOUNT",
            "riskType": "SUSPICIOUS_ACCOUNT",
            "reasonCode": "SUSPICIOUS_RECIPIENT_ACCOUNT",
            "freshnessSeconds": 86400,
        }:
            raise RuleAnalysisRequestError("R005 v4 binding mismatch")
        if index in _SIGNALS and rule.condition_definition != {
            "contractVersion": "SCN001-002-contract-v1",
            "signal": _SIGNALS[index],
        }:
            raise RuleAnalysisRequestError("Rule v4 condition mismatch")


def _within_strict(earlier: datetime | None, later: datetime | None, minutes: int) -> bool:
    return (
        earlier is not None
        and later is not None
        and earlier < later
        and later - earlier <= timedelta(minutes=minutes)
    )


def _in_window(at: datetime | None, t: datetime, minutes: int) -> bool:
    return at is not None and t - timedelta(minutes=minutes) <= at <= t


def _wire(value: datetime) -> str:
    return value.isoformat().replace("+00:00", "Z")


def _krw(value: Decimal | None) -> str:
    if value is None or value != value.to_integral_value():
        raise RuleAnalysisRequestError("Non-integral KRW Evidence source")
    return str(int(value))


def _rule_set_version(request: RuleAnalysisRequestV3) -> str:
    lines = ["rule-plan-v4"]
    for index, rule in enumerate(request.rule_versions):
        lines.append(
            f"{index + 1}\t{rule.rule_version_id}\t{rule.rule_code}"
            f"\tR{index + 1:03d}\t{rule.version_number}"
        )
    return hashlib.sha256(("\n".join(lines) + "\n").encode()).hexdigest()


def _observation(index: int, request: RuleAnalysisRequestV3) -> tuple[dict[str, object], datetime]:
    f = request.scenario_facts
    t = request.evaluation_cutoff_at
    source = {"sourceVersion": f.source_version}
    mock = {"mockSourceCode": "LOCAL_FDS_MOCK", "mockSourceVersion": "v1"}
    if index == 0:
        return {"observedAmount": str(request.transaction.amount), "amountThreshold": "10000000"}, t
    if index == 1:
        assert f.device_first_seen_at is not None and f.device_event_id is not None
        return {
            **source,
            **mock,
            "deviceEventId": str(f.device_event_id),
            "deviceFirstSeenAt": _wire(f.device_first_seen_at),
            "deviceStatus": f.device_status,
            "deviceCoverageFactId": str(f.device_coverage_fact_id),
            "deviceTrustFactId": str(f.device_trust_fact_id),
        }, f.device_first_seen_at
    if index == 2:
        assert f.password_changed_at is not None and f.limit_changed_at is not None
        return {
            **source,
            **mock,
            "passwordEventId": str(f.password_event_id),
            "passwordChangedAt": _wire(f.password_changed_at),
            "limitEventId": str(f.limit_event_id),
            "limitChangedAt": _wire(f.limit_changed_at),
            "limitScope": f.limit_scope,
            "previousLimit": _krw(f.previous_limit),
            "newLimit": _krw(f.new_limit),
            "identityStatus": f.identity_status,
            "limitFactId": str(f.limit_fact_id),
            "identityFactId": str(f.identity_fact_id) if f.identity_fact_id else None,
        }, f.limit_changed_at
    if index == 3:
        assert f.beneficiary_registered_at is not None
        return {
            **source,
            **mock,
            "beneficiaryEventId": str(f.beneficiary_event_id),
            "beneficiaryRegisteredAt": _wire(f.beneficiary_registered_at),
            "recipientStatus": f.recipient_status,
            "transferCoverageFactId": str(f.transfer_coverage_fact_id),
        }, f.beneficiary_registered_at
    if index == 4:
        risk = request.external_risk
        return {
            "providerCode": risk.provider_code,
            "providerAsOf": _wire(risk.provider_as_of),
            "lookedUpAt": _wire(risk.looked_up_at),
            "freshnessSeconds": 86400,
        }, risk.provider_as_of
    if index == 5:
        return {
            **source,
            **mock,
            "baselineCount": f.baseline_count,
            "baselineDays": f.baseline_days,
            "baselineTotal": _krw(f.baseline_total),
            "observedAmount": str(request.transaction.amount),
            "transferCoverageFactId": str(f.transfer_coverage_fact_id),
        }, t
    if index == 6:
        risk = request.external_risk
        return {
            **source,
            "providerCode": risk.provider_code,
            "providerAsOf": _wire(risk.provider_as_of),
            "lookedUpAt": _wire(risk.looked_up_at),
            "deviceMatched": True,
        }, risk.provider_as_of
    if index == 7:
        assert f.app_security_at is not None
        return {
            **source,
            **mock,
            "appSecurityStatus": f.app_security_status,
            "signalAt": _wire(f.app_security_at),
            "appSecurityFactId": str(f.app_security_fact_id),
        }, f.app_security_at
    if index == 8:
        assert f.limit_changed_at is not None
        return {
            **source,
            **mock,
            "limitEventId": str(f.limit_event_id),
            "previousLimit": _krw(f.previous_limit),
            "newLimit": _krw(f.new_limit),
            "limitChangedAt": _wire(f.limit_changed_at),
            "limitFactId": str(f.limit_fact_id),
        }, f.limit_changed_at
    assert f.network_region_at is not None
    return {
        **source,
        **mock,
        "networkRegionStatus": f.network_region_status,
        "signalAt": _wire(f.network_region_at),
        "networkRegionFactId": str(f.network_region_fact_id),
    }, f.network_region_at
