"""Spring Boot's internal report-generation endpoint."""

import logging
from typing import Annotated

from fastapi import APIRouter, Depends

from finguardops_ai.core.config import get_settings
from finguardops_ai.schemas.ai_report import ReportRequest, ReportResponse
from finguardops_ai.services.ai_report import (
    AiReportService,
    get_ai_report_service,
    observed_model_version,
)
from finguardops_ai.services.ollama_client import OllamaClient

router = APIRouter(tags=["ai-report-internal"])
logger = logging.getLogger(__name__)


@router.get("/v1/ai-reports/model")
def model_identity() -> dict[str, str]:
    settings = get_settings()
    return {
        "modelVersion": observed_model_version(settings, OllamaClient(settings)),
        "promptVersion": settings.ai_report_prompt_version,
    }


@router.post("/v1/ai-reports", response_model=ReportResponse)
def generate_report(
    request: ReportRequest,
    service: Annotated[AiReportService, Depends(get_ai_report_service)],
) -> ReportResponse:
    result = service.generate(request)
    logger.info(
        "event=ai_report_generated status=%s source=%s failureCode=%s",
        result.status,
        result.source or "none",
        result.failureCode or "none",
    )
    return result
