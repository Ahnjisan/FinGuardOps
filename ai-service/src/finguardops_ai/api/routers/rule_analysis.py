"""HTTP endpoint for synchronous Rule v1 analysis."""

from typing import Annotated

from fastapi import APIRouter, Depends, Request, status

from finguardops_ai.mappers import RuleAnalysisResponseMapper
from finguardops_ai.schemas.rule_analysis import (
    RuleAnalysisRequest,
    RuleAnalysisRequestV2,
    RuleAnalysisResponse,
)
from finguardops_ai.services.rule_analysis import (
    RuleAnalysisService,
    get_rule_analysis_service,
)

router = APIRouter(tags=["rule-analysis"])


@router.post(
    "/v1/rule-analysis",
    response_model=RuleAnalysisResponse,
    status_code=status.HTTP_200_OK,
)
def analyze_rule_v1(
    request_dto: RuleAnalysisRequest,
    request: Request,
    service: Annotated[RuleAnalysisService, Depends(get_rule_analysis_service)],
) -> RuleAnalysisResponse:
    return _analyze(request_dto, request, service)


@router.post(
    "/v2/rule-analysis",
    response_model=RuleAnalysisResponse,
    status_code=status.HTTP_200_OK,
)
def analyze_rule_v2(
    request_dto: RuleAnalysisRequestV2,
    request: Request,
    service: Annotated[RuleAnalysisService, Depends(get_rule_analysis_service)],
) -> RuleAnalysisResponse:
    return _analyze(request_dto, request, service)


def _analyze(
    request_dto: RuleAnalysisRequest,
    request: Request,
    service: RuleAnalysisService,
) -> RuleAnalysisResponse:
    analysis_result = service.analyze(request_dto)
    return RuleAnalysisResponseMapper.to_dto(
        transaction_id=request_dto.transaction.transaction_id,
        trace_id=request.state.trace_id,
        analysis_result=analysis_result,
    )
