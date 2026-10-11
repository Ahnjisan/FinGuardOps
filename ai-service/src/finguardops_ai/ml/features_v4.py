"""Two cutoff-fixed, Mock-proven source-to-use indicators layered on v3."""

from datetime import datetime
from decimal import Decimal

from finguardops_ai.ml.features_v3 import FEATURE_NAMES as BASE_NAMES
from finguardops_ai.ml.features_v3 import extract_features as base_features
from finguardops_ai.schemas.scenario_rule import (
    ScenarioAggregationFactsDto,
    ScenarioFactsDto,
    ScenarioFundFlowFactsDto,
)

FEATURE_VERSION = "fraud-feature-v4"
FEATURE_NAMES = (*BASE_NAMES, "inbound_atm_drain", "loan_new_recipient_drain")


def extract_features(
    *,
    amount: Decimal,
    channel: str,
    cutoff: datetime,
    facts: ScenarioFactsDto,
    aggregation: ScenarioAggregationFactsDto,
    fund_flow: ScenarioFundFlowFactsDto,
    transfer: bool = True,
) -> tuple[float, ...]:
    if fund_flow.source_version != "SCN006-007-contract-v1":
        raise ValueError("INVALID_FEATURES")
    flows = (fund_flow.atm, fund_flow.loan)
    for flow in flows:
        if flow.status not in {"AVAILABLE", "UNKNOWN"} or not 0 <= flow.ratio_basis_points <= 10000:
            raise ValueError("INVALID_FEATURES")
    return (
        *base_features(
            amount=amount,
            channel=channel,
            cutoff=cutoff,
            facts=facts,
            aggregation=aggregation,
            transfer=transfer,
        ),
        float(flows[0].status == "AVAILABLE" and flows[0].ratio_basis_points >= 8000),
        float(flows[1].status == "AVAILABLE" and flows[1].ratio_basis_points >= 8000),
    )
