from typing import Annotated

from fastapi import APIRouter, Depends, status

from finguardops_ai.core.config import Settings, get_settings
from finguardops_ai.schemas.health import HealthResponse
from finguardops_ai.services.health import get_health

router = APIRouter(tags=["health"])


@router.get(
    "/health",
    response_model=HealthResponse,
    status_code=status.HTTP_200_OK,
)
def read_health(settings: Annotated[Settings, Depends(get_settings)]) -> HealthResponse:
    return get_health(settings)
