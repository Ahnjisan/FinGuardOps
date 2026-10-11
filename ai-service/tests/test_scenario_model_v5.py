"""Pinned synthetic flow features; no observed performance claim."""

from copy import deepcopy

import pytest
from pydantic import ValidationError

from finguardops_ai.ml.model import load_model
from finguardops_ai.schemas.ml_inference import MlInferenceRequest
from finguardops_ai.services.ml_inference import infer
from test_scenario_rule_v6 import _request_v6, _unknown


def _ml_payload() -> dict:
    rule = _request_v6("atm")
    return {
        "transactionId": rule["transaction"]["transactionId"],
        "evaluationCutoffAt": rule["evaluationCutoffAt"],
        "amount": rule["transaction"]["amount"],
        "transactionType": rule["transaction"]["transactionType"],
        "channel": "ATM",
        "featureVersion": "fraud-feature-v4",
        "scoringPolicyVersion": "rule-ml-policy-v5",
        "modelVersion": "fraud-logistic-v5",
        "modelSha256": load_model("fraud-logistic-v5").sha256,
        "events": [],
        "scenarioFacts": rule["scenarioFacts"],
        "aggregationFacts": rule["aggregationFacts"],
        "fundFlowFacts": rule["fundFlowFacts"],
    }


def test_verified_flow_increases_pinned_probability() -> None:
    raw = _ml_payload()
    with_signal = infer(MlInferenceRequest.model_validate(raw))
    missing = deepcopy(raw)
    missing["fundFlowFacts"]["atm"] = _unknown("ACCOUNT_HISTORY_INCOMPLETE")
    without_signal = infer(MlInferenceRequest.model_validate(missing))
    assert with_signal.modelSha256 == raw["modelSha256"]
    assert with_signal.probabilityBasisPoints > without_signal.probabilityBasisPoints


def test_v5_rejects_missing_fund_facts() -> None:
    raw = _ml_payload()
    raw.pop("fundFlowFacts")
    with pytest.raises(ValidationError):
        MlInferenceRequest.model_validate(raw)
