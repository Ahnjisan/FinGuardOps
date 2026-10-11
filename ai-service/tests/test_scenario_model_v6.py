"""Pinned synthetic SCN-008 feature and model identity."""

from copy import deepcopy

import pytest
from pydantic import ValidationError

from finguardops_ai.ml.model import load_model
from finguardops_ai.schemas.ml_inference import MlInferenceRequest
from finguardops_ai.services.ml_inference import infer
from test_scenario_rule_v7 import _request_v7


def _ml_payload() -> dict:
    rule = _request_v7()
    return {
        "transactionId": rule["transaction"]["transactionId"],
        "evaluationCutoffAt": rule["evaluationCutoffAt"],
        "amount": rule["transaction"]["amount"],
        "transactionType": rule["transaction"]["transactionType"],
        "channel": "MOBILE_BANKING",
        "featureVersion": "fraud-feature-v5",
        "scoringPolicyVersion": "rule-ml-policy-v6",
        "modelVersion": "fraud-logistic-v6",
        "modelSha256": load_model("fraud-logistic-v6").sha256,
        "events": [],
        "scenarioFacts": rule["scenarioFacts"],
        "aggregationFacts": rule["aggregationFacts"],
        "fundFlowFacts": rule["fundFlowFacts"],
        "openBankingFacts": rule["openBankingFacts"],
    }


def test_verified_chain_increases_pinned_probability() -> None:
    raw = _ml_payload()
    with_signal = infer(MlInferenceRequest.model_validate(raw))
    missing = deepcopy(raw)
    missing["openBankingFacts"].update(
        status="UNKNOWN",
        reasonCode="MOCK_PARTIAL_OR_FAILED",
        historyCoverageFactId=None,
        concentrationStartedAt=None,
        concentrationCompletedAt=None,
        concentrationAmount="0",
        resendAmount="0",
        ratioBasisPoints=0,
        stages=[],
    )
    without_signal = infer(MlInferenceRequest.model_validate(missing))
    assert with_signal.modelSha256 == raw["modelSha256"]
    assert with_signal.probabilityBasisPoints > without_signal.probabilityBasisPoints


def test_v6_rejects_missing_chain_facts() -> None:
    raw = _ml_payload()
    raw.pop("openBankingFacts")
    with pytest.raises(ValidationError):
        MlInferenceRequest.model_validate(raw)
