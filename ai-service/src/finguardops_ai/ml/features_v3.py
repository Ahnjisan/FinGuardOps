"""Synthetic aggregate features fixed to the evaluated transaction cutoff."""

from datetime import datetime
from decimal import Decimal

from finguardops_ai.ml.features_v2 import FEATURE_NAMES as BASE_NAMES
from finguardops_ai.ml.features_v2 import extract_features as base_features
from finguardops_ai.schemas.scenario_rule import ScenarioAggregationFactsDto, ScenarioFactsDto

FEATURE_VERSION = "fraud-feature-v3"
FEATURE_NAMES = (*BASE_NAMES, "outbound_aggregate", "inbound_aggregate")


def extract_features(
    *,
    amount: Decimal,
    channel: str,
    cutoff: datetime,
    facts: ScenarioFactsDto,
    aggregation: ScenarioAggregationFactsDto,
    transfer: bool = True,
) -> tuple[float, ...]:
    if aggregation.source_version != "SCN004-005-contract-v1":
        raise ValueError("INVALID_FEATURES")
    for window in (aggregation.outbound, aggregation.inbound):
        if (
            window.transfer_count < 0
            or window.distinct_count < 0
            or window.unknown_count < 0
            or window.total_amount < 0
            or window.distinct_count > window.transfer_count
        ):
            raise ValueError("INVALID_FEATURES")
    outbound = aggregation.outbound
    inbound = aggregation.inbound
    out_hit = (
        transfer
        and outbound.coverage_fact_id is not None
        and outbound.unknown_count == 0
        and outbound.transfer_count >= 3
        and outbound.distinct_count >= 3
        and outbound.total_amount >= Decimal("3000000")
    )
    in_hit = (
        transfer
        and inbound.coverage_fact_id is not None
        and inbound.unknown_count == 0
        and inbound.transfer_count >= 3
        and inbound.distinct_count >= 3
        and inbound.total_amount >= Decimal("5000000")
    )
    return (
        *base_features(
            amount=amount, channel=channel, cutoff=cutoff, facts=facts, transfer=transfer
        ),
        float(out_hit),
        float(in_hit),
    )
