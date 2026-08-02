from typing import Literal

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """Immutable application settings loaded from the process environment."""

    model_config = SettingsConfigDict(
        env_prefix="FINGUARDOPS_AI_",
        extra="ignore",
        frozen=True,
    )

    app_name: str = "FinGuardOps AI Service"
    api_prefix: Literal["/api"] = "/api"
    service_name: Literal["ai-service"] = "ai-service"


def get_settings() -> Settings:
    return Settings()
