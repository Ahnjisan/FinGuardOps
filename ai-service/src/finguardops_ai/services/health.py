from finguardops_ai.core.config import Settings
from finguardops_ai.schemas.health import HealthResponse


def get_health(settings: Settings) -> HealthResponse:
    """Return the health of this process without probing external dependencies."""
    return HealthResponse(status="UP", service=settings.service_name)
