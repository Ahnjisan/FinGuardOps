"""Stable public error envelope for the Rule analysis HTTP boundary."""

from pydantic import BaseModel, ConfigDict, Field
from pydantic.alias_generators import to_camel


class RuleAnalysisErrorDto(BaseModel):
    model_config = ConfigDict(
        alias_generator=to_camel,
        extra="forbid",
        frozen=True,
        strict=True,
        validate_by_alias=True,
        validate_by_name=False,
        serialize_by_alias=True,
    )


class RuleAnalysisFieldError(RuleAnalysisErrorDto):
    field: str
    code: str
    reason: str


class RuleAnalysisErrorResponse(RuleAnalysisErrorDto):
    code: str
    message: str
    trace_id: str
    field_errors: tuple[RuleAnalysisFieldError, ...] = Field(default_factory=tuple)
