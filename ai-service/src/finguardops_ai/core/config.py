from typing import Literal

from pydantic import model_validator
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
    ollama_base_url: str = "http://host.docker.internal:11434"
    ollama_model: str = "qwen3.5:4b"
    ollama_model_digest: str = ""
    ollama_quantization: str = ""
    ai_report_prompt_version: str = "ai-report-local-v1"
    ai_report_timeout_seconds: float = 45.0
    ai_report_max_output_tokens: int = 384

    @model_validator(mode="after")
    def validate_report_settings(self) -> "Settings":
        digest = self.ollama_model_digest
        quantization = self.ollama_quantization
        if (digest or quantization) and (
            len(digest) != 64
            or any(char not in "0123456789abcdef" for char in digest)
            or not quantization
            or len(quantization) > 64
        ):
            raise ValueError("Ollama digest and quantization must be pinned together")
        if not 1 <= self.ai_report_max_output_tokens <= 1024:
            raise ValueError("AI report output limit must be between 1 and 1024")
        if not 1 <= self.ai_report_timeout_seconds <= 45:
            raise ValueError("AI report timeout must be between 1 and 45 seconds")
        return self


def get_settings() -> Settings:
    return Settings()
