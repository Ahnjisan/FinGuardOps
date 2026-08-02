from typing import Literal

from pydantic import BaseModel, ConfigDict


class HealthResponse(BaseModel):
    """Stable response contract for the process health endpoint."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    status: Literal["UP"]
    service: Literal["ai-service"]
