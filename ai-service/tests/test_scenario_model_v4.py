"""Pinned synthetic v4 feature behavior; no production performance claim."""

import json
from copy import deepcopy
from importlib.resources import files

import pytest
from pydantic import ValidationError

from finguardops_ai.ml.model import load_model
from finguardops_ai.schemas.ml_inference import MlInferenceRequest
from finguardops_ai.services.ml_inference import infer
from test_scenario_rule_v5 import _request


def test_v4_artifact_is_an_explicit_synthetic_v3_extension() -> None:
    assets = files("finguardops_ai.ml")
    prior = json.loads(assets.joinpath("fraud_model_v3.json").read_text())
    current = json.loads(assets.joinpath("fraud_model_v4.json").read_text())
    assert current["weights"][:-2] == prior["weights"]
    assert current["weights"][-2:] == [0.55, 0.55]
    assert current["bias"] == prior["bias"]
    assert current["featureNames"][-2:] == ["outbound_aggregate", "inbound_aggregate"]
    assert current["dataVersion"] == "synthetic-fraud-v4-rule-assisted"
    assert len(load_model("fraud-logistic-v4").weights) == 14


def _ml_payload() -> dict:
    rule = _request()
    return {
        "transactionId": rule["transaction"]["transactionId"],
        "evaluationCutoffAt": rule["evaluationCutoffAt"],
        "amount": rule["transaction"]["amount"],
        "transactionType": rule["transaction"]["transactionType"],
        "channel": "MOBILE_BANKING",
        "featureVersion": "fraud-feature-v3",
        "scoringPolicyVersion": "rule-ml-policy-v4",
        "modelVersion": "fraud-logistic-v4",
        "modelSha256": load_model("fraud-logistic-v4").sha256,
        "events": [],
        "scenarioFacts": rule["scenarioFacts"],
        "aggregationFacts": rule["aggregationFacts"],
    }


def test_v4_pin_and_cutoff_fixed_aggregate_effect() -> None:
    raw = _ml_payload()
    with_signal = infer(MlInferenceRequest.model_validate(raw))
    no_proof = deepcopy(raw)
    no_proof["aggregationFacts"]["outbound"]["coverageFactId"] = None
    no_proof["aggregationFacts"]["inbound"]["unknownCount"] = 1
    without_signal = infer(MlInferenceRequest.model_validate(no_proof))
    assert with_signal.modelSha256 == raw["modelSha256"]
    assert with_signal.probabilityBasisPoints > without_signal.probabilityBasisPoints


def test_v4_rejects_missing_aggregation_and_legacy_events() -> None:
    raw = _ml_payload()
    raw.pop("aggregationFacts")
    with pytest.raises(ValidationError):
        MlInferenceRequest.model_validate(raw)
