"""SCN-001/002 synthetic features, derived only from cutoff-fixed trusted facts."""

from datetime import datetime, timedelta
from decimal import Decimal
from math import log1p

from finguardops_ai.schemas.scenario_rule import ScenarioFactsDto

FEATURE_VERSION = "fraud-feature-v2"
FEATURE_NAMES = (
    "log_amount", "is_mobile", "baseline_ratio_capped", "baseline_available",
    "new_unverified_device", "first_recipient", "password_limit_sequence",
    "material_limit_uplift", "app_alert", "overseas_network",
    "exact_provider_device_match", "identity_failed",
)


def extract_features(*, amount: Decimal, channel: str, cutoff: datetime,
                     facts: ScenarioFactsDto, transfer: bool = True) -> tuple[float, ...]:
    if not amount.is_finite() or amount <= 0 or amount > Decimal("999999999999999"):
        raise ValueError("INVALID_AMOUNT")
    if cutoff.tzinfo is None:
        raise ValueError("INVALID_CUTOFF")
    if facts.source_version != "SCN001-002-contract-v1":
        raise ValueError("INVALID_FEATURES")
    for at in (facts.beneficiary_registered_at, facts.device_first_seen_at,
               facts.password_changed_at, facts.limit_changed_at,
               facts.app_security_at, facts.network_region_at):
        if at is not None and (at.tzinfo is None or at > cutoff):
            raise ValueError("FEATURE_OUTSIDE_CUTOFF")
    pairs = ((facts.beneficiary_event_id, facts.beneficiary_registered_at),
             (facts.device_event_id, facts.device_first_seen_at),
             (facts.password_event_id, facts.password_changed_at),
             (facts.limit_event_id, facts.limit_changed_at))
    if any((event_id is None) != (at is None) for event_id, at in pairs):
        raise ValueError("INCOMPLETE_EVENT_PROVENANCE")
    if facts.device_status in {"NEW_UNVERIFIED", "NEW_VERIFIED"} and facts.device_event_id is None:
        raise ValueError("INCOMPLETE_DEVICE_PROVENANCE")
    if facts.limit_status == "RAISED" and (facts.limit_event_id is None
            or facts.previous_limit is None or facts.new_limit is None
            or facts.new_limit <= facts.previous_limit):
        raise ValueError("INCOMPLETE_LIMIT_PROVENANCE")
    if facts.app_security_status == "ALERT" and facts.app_security_at is None:
        raise ValueError("INCOMPLETE_APP_PROVENANCE")
    if facts.network_region_status == "OVERSEAS" and facts.network_region_at is None:
        raise ValueError("INCOMPLETE_REGION_PROVENANCE")
    if not transfer:
        return (log1p(float(amount)) / 20.0, float(channel == "MOBILE_BANKING"),
                0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
    baseline = facts.baseline_status == "AVAILABLE" and facts.baseline_count >= 10
    if baseline and (facts.baseline_total <= 0 or facts.baseline_days < 3):
        raise ValueError("INVALID_FEATURES")
    ratio = (min(Decimal(20), amount * facts.baseline_count / facts.baseline_total) / 20
             if baseline else Decimal(0))
    sequence = (facts.limit_status == "RAISED" and facts.password_changed_at is not None
                and facts.limit_changed_at is not None
                and facts.password_changed_at < facts.limit_changed_at < cutoff
                and facts.limit_changed_at - facts.password_changed_at <= timedelta(minutes=30)
                and cutoff - facts.limit_changed_at <= timedelta(minutes=30)
                and cutoff - facts.password_changed_at <= timedelta(minutes=60))
    material = (sequence and facts.previous_limit is not None and facts.new_limit is not None
                and facts.new_limit >= facts.previous_limit * 2
                and facts.new_limit - facts.previous_limit >= Decimal("5000000"))
    app = (facts.app_security_status == "ALERT" and facts.app_security_at is not None
           and cutoff - timedelta(hours=24) <= facts.app_security_at <= cutoff)
    overseas = (facts.network_region_status == "OVERSEAS"
                and facts.network_region_at is not None
                and cutoff - timedelta(minutes=15) <= facts.network_region_at <= cutoff)
    return (
        log1p(float(amount)) / 20.0,
        float(channel == "MOBILE_BANKING"),
        float(ratio),
        float(baseline),
        float(facts.device_status == "NEW_UNVERIFIED"),
        float(facts.recipient_status == "FIRST"),
        float(sequence),
        float(material),
        float(app),
        float(overseas),
        float(facts.exact_provider_device_match),
        float(facts.identity_status == "FAILED"),
    )
