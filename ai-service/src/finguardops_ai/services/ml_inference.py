"""Fraud ML inference service; does not make transaction decisions."""

from finguardops_ai.ml.features import extract_features
from finguardops_ai.ml.features_v2 import extract_features as extract_features_v2
from finguardops_ai.ml.features_v3 import extract_features as extract_features_v3
from finguardops_ai.ml.features_v4 import extract_features as extract_features_v4
from finguardops_ai.ml.model import ModelUnavailable, load_model
from finguardops_ai.schemas.ml_inference import MlInferenceRequest, MlInferenceResponse


def infer(request: MlInferenceRequest) -> MlInferenceResponse:
    model = load_model(request.modelVersion)
    if request.modelVersion != model.version or request.modelSha256 != model.sha256:
        raise ModelUnavailable("MODEL_VERSION_MISMATCH")
    features = (
        extract_features_v4(
            amount=request.amount,
            channel=request.channel,
            cutoff=request.evaluationCutoffAt,
            facts=request.scenarioFacts,
            aggregation=request.aggregationFacts,
            fund_flow=request.fundFlowFacts,
            transfer=request.transactionType in {"ACCOUNT_TRANSFER", "OPEN_BANKING_TRANSFER"},
        )
        if request.fundFlowFacts is not None
        else extract_features_v3(
            amount=request.amount,
            channel=request.channel,
            cutoff=request.evaluationCutoffAt,
            facts=request.scenarioFacts,
            aggregation=request.aggregationFacts,
            transfer=request.transactionType in {"ACCOUNT_TRANSFER", "OPEN_BANKING_TRANSFER"},
        )
        if request.aggregationFacts is not None
        else extract_features_v2(
            amount=request.amount,
            channel=request.channel,
            cutoff=request.evaluationCutoffAt,
            facts=request.scenarioFacts,
            transfer=request.transactionType in {"ACCOUNT_TRANSFER", "OPEN_BANKING_TRANSFER"},
        )
        if request.scenarioFacts is not None
        else extract_features(
            amount=request.amount,
            transaction_type=request.transactionType,
            channel=request.channel,
            cutoff=request.evaluationCutoffAt,
            events=request.events,
        )
    )
    probability = model.probability_basis_points(features)
    return MlInferenceResponse(
        transactionId=request.transactionId,
        evaluationCutoffAt=request.evaluationCutoffAt,
        featureVersion=request.featureVersion,
        scoringPolicyVersion=request.scoringPolicyVersion,
        modelVersion=model.version,
        modelSha256=model.sha256,
        probabilityBasisPoints=probability,
        reasonCode="ML_RISK_SIGNAL" if probability > 5000 else "ML_BELOW_THRESHOLD",
    )
