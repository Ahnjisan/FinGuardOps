"""Strict, allowlisted report projection shared with the Spring worker."""

from typing import Literal

from pydantic import BaseModel, ConfigDict, Field


class RuleEvidence(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    ruleCode: str = Field(pattern=r"^[A-Z][A-Z0-9_]{0,63}$")
    ruleVersion: str = Field(pattern=r"^[0-9]{1,10}$")
    reasonCode: str = Field(pattern=r"^[A-Z][A-Z0-9_]{0,63}$")
    scoreContribution: int = Field(ge=0, le=100)


class ReportRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    caseId: str = Field(pattern=r"^[0-9a-f-]{36}$")
    detectionResultVersion: int = Field(ge=1)
    riskLevel: Literal["HIGH", "CRITICAL"]
    riskScore: int = Field(ge=0, le=100)
    ruleSetVersion: str = Field(pattern=r"^[A-Za-z0-9_.-]{1,64}$")
    ruleEvidence: list[RuleEvidence] = Field(min_length=1, max_length=20)
    traceId: str = Field(min_length=8, max_length=64)


class KeyReason(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    reasonCode: str = Field(pattern=r"^[A-Z][A-Z0-9_]{0,63}$")
    description: str = Field(min_length=1, max_length=240)


class ReportContent(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    summary: str = Field(min_length=1, max_length=600)
    keyReasons: list[KeyReason] = Field(min_length=1, max_length=20)
    investigationChecklist: list[str] = Field(min_length=1, max_length=8)


class ProviderAttempt(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    provider: Literal["OLLAMA_LOCAL"] = "OLLAMA_LOCAL"
    modelDigest: str | None = None
    quantization: str | None = None
    inputTokens: int | None = Field(default=None, ge=0)
    outputTokens: int | None = Field(default=None, ge=0)
    latencyMs: int = Field(ge=0)
    outcome: Literal[
        "COMPLETED", "TIMEOUT", "CONNECTION_FAILED", "PROVIDER_ERROR", "INVALID_OUTPUT"
    ]


class ReportResponse(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    status: Literal["COMPLETED", "FALLBACK_COMPLETED", "FAILED"]
    source: Literal["LLM", "TEMPLATE_FALLBACK"] | None
    content: ReportContent | None
    failureCode: str | None
    fallbackTriggerCode: str | None
    modelVersion: str
    promptVersion: str
    attempts: list[ProviderAttempt] = Field(max_length=2)
