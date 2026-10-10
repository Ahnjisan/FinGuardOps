"""Internal local fraud ML endpoint."""

import logging
import time

from fastapi import APIRouter, HTTPException

from finguardops_ai.ml.model import ModelUnavailable, load_model
from finguardops_ai.schemas.ml_inference import MlInferenceRequest, MlInferenceResponse
from finguardops_ai.services.ml_inference import infer

router = APIRouter(tags=["fraud-ml-internal"])
logger = logging.getLogger(__name__)


@router.get("/v1/ml-inference/model")
def model_status() -> dict[str, object]:
    try:
        model = load_model()
        return {
            "ready": True,
            "modelVersion": model.version,
            "modelSha256": model.sha256,
            "featureVersion": "fraud-feature-v1",
        }
    except ModelUnavailable as exc:
        raise HTTPException(status_code=503, detail={"code": exc.code}) from exc


@router.post("/v1/ml-inference", response_model=MlInferenceResponse)
def ml_inference(request: MlInferenceRequest) -> MlInferenceResponse:
    started = time.perf_counter()
    try:
        result = infer(request)
        logger.info(
            "event=ml_inference result=success modelVersion=%s durationMs=%.3f",
            result.modelVersion,
            (time.perf_counter() - started) * 1000,
        )
        return result
    except ModelUnavailable as exc:
        logger.warning(
            "event=ml_inference result=failure failureCategory=%s durationMs=%.3f",
            exc.code,
            (time.perf_counter() - started) * 1000,
        )
        raise HTTPException(status_code=503, detail={"code": exc.code}) from exc
    except ValueError as exc:
        logger.warning(
            "event=ml_inference result=failure failureCategory=INVALID_FEATURES durationMs=%.3f",
            (time.perf_counter() - started) * 1000,
        )
        raise HTTPException(status_code=422, detail={"code": str(exc)}) from exc
