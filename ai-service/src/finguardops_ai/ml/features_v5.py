"""Pinned local synthetic SCN-008 chain indicator layered on v4."""

from datetime import datetime
from decimal import Decimal

from finguardops_ai.ml.features_v4 import FEATURE_NAMES as BASE_NAMES
from finguardops_ai.ml.features_v4 import extract_features as base_features
from finguardops_ai.schemas.scenario_rule import (
    ScenarioAggregationFactsDto,
    ScenarioFactsDto,
    ScenarioFundFlowFactsDto,
    ScenarioOpenBankingFactsDto,
)

FEATURE_VERSION = "fraud-feature-v5"
FEATURE_NAMES = (*BASE_NAMES, "verified_open_banking_chain")


def extract_features(
    *,
    amount: Decimal,
    channel: str,
    cutoff: datetime,
    facts: ScenarioFactsDto,
    aggregation: ScenarioAggregationFactsDto,
    fund_flow: ScenarioFundFlowFactsDto,
    open_banking: ScenarioOpenBankingFactsDto,
    transfer: bool = True,
) -> tuple[float, ...]:
    if open_banking.source_version != "SCN008-contract-v1":
        raise ValueError("INVALID_FEATURES")
    return (
        *base_features(
            amount=amount,
            channel=channel,
            cutoff=cutoff,
            facts=facts,
            aggregation=aggregation,
            fund_flow=fund_flow,
            transfer=transfer,
        ),
        float(open_banking.hit()),
    )
