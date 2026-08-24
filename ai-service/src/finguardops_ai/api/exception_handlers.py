"""Exception-to-contract mappings for the Rule analysis API."""

import logging
from collections.abc import Mapping, Sequence
from types import MappingProxyType
from uuid import uuid4

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from pydantic import BaseModel

from finguardops_ai.api.middleware import TRACE_HEADER_NAME
from finguardops_ai.mappers import (
    RuleAnalysisRequestError,
    RuleAnalysisRequestErrorCategory,
)
from finguardops_ai.rules.v1 import (
    RuleEvidenceError,
    RuleExecutionPlanError,
    RuleExecutionPlanErrorCategory,
    RuleExecutionPlanRunnerError,
    RuleExecutionPlanRunnerErrorCategory,
    RuleScoringError,
)
from finguardops_ai.rules.v1.execution_plan import RuleExecutionPlanErrorOrigin
from finguardops_ai.schemas.errors import (
    RuleAnalysisErrorResponse,
    RuleAnalysisFieldError,
)
from finguardops_ai.schemas.rule_analysis import (
    ExternalRiskMatchRequest,
    ExternalRiskSnapshotRequest,
    RuleAnalysisRequest,
    RuleAnalysisRequestV2,
    RuleBehaviorEventSnapshotRequest,
    RuleTransactionSnapshotRequest,
    RuleVersionSnapshotRequest,
)

_INVALID_REQUEST_MESSAGE = "Rule 분석 요청 형식을 확인해 주세요."
_RULE_CONTRACT_MESSAGE = "Rule 분석 요청 계약을 확인해 주세요."
_UNSUPPORTED_CAPABILITY_MESSAGE = "현재 배포된 Rule 실행 기능을 사용할 수 없습니다."
_INTERNAL_ERROR_MESSAGE = "Rule 분석 요청을 처리하는 중 오류가 발생했습니다."

logger = logging.getLogger(__name__)


def register_rule_analysis_exception_handlers(application: FastAPI) -> None:
    application.add_exception_handler(RequestValidationError, _request_validation_error)
    application.add_exception_handler(RuleAnalysisRequestError, _request_contract_error)
    application.add_exception_handler(RuleExecutionPlanError, _plan_error)
    application.add_exception_handler(RuleExecutionPlanRunnerError, _runner_error)
    application.add_exception_handler(RuleScoringError, _internal_rule_error)
    application.add_exception_handler(RuleEvidenceError, _internal_rule_error)


async def _request_validation_error(
    request: Request,
    exc: RequestValidationError,
) -> JSONResponse:
    trace_id = _trace_id(request)
    logger.warning(
        "Rule analysis request failed traceId=%s code=INVALID_REQUEST category=wire_validation",
        trace_id,
    )
    return _error_response(
        status_code=400,
        code="INVALID_REQUEST",
        message=_INVALID_REQUEST_MESSAGE,
        trace_id=trace_id,
        field_errors=_validation_field_errors(request, exc),
    )


async def _request_contract_error(
    request: Request,
    exc: RuleAnalysisRequestError,
) -> JSONResponse:
    trace_id = _trace_id(request)
    if exc.category is RuleAnalysisRequestErrorCategory.RULE_CONTRACT_ERROR:
        return _rule_contract_response(trace_id, exc.category.value)
    return _internal_error_response(trace_id, "request_mapper")


async def _plan_error(request: Request, exc: RuleExecutionPlanError) -> JSONResponse:
    trace_id = _trace_id(request)
    if exc.origin is RuleExecutionPlanErrorOrigin.DEPLOYED_CAPABILITY:
        if exc.category is not RuleExecutionPlanErrorCategory.UNSUPPORTED_RULE_CAPABILITY:
            return _internal_error_response(trace_id, "execution_plan_origin_mismatch")
        return _unsupported_capability_response(trace_id, exc.category.value)
    if exc.origin is RuleExecutionPlanErrorOrigin.SERVER_CONFIGURATION:
        return _internal_error_response(trace_id, exc.origin.value)
    if (
        exc.origin is RuleExecutionPlanErrorOrigin.REQUEST_CONTRACT
        and exc.category in _RULE_CONTRACT_PLAN_CATEGORIES
    ):
        return _rule_contract_response(
            trace_id,
            exc.category.value,
            field="ruleVersions",
        )
    return _internal_error_response(trace_id, "execution_plan")


async def _runner_error(
    request: Request,
    exc: RuleExecutionPlanRunnerError,
) -> JSONResponse:
    trace_id = _trace_id(request)
    if exc.category is RuleExecutionPlanRunnerErrorCategory.UNSUPPORTED_RULE_CAPABILITY:
        return _unsupported_capability_response(trace_id, exc.category.value)
    return _internal_error_response(trace_id, exc.category.value)


async def _internal_rule_error(
    request: Request,
    exc: RuleScoringError | RuleEvidenceError,
) -> JSONResponse:
    return _internal_error_response(_trace_id(request), exc.category.value)


_RULE_CONTRACT_PLAN_CATEGORIES = frozenset(
    {
        RuleExecutionPlanErrorCategory.NO_EXECUTABLE_RULE_VERSION,
        RuleExecutionPlanErrorCategory.MULTIPLE_EXECUTABLE_RULE_VERSIONS,
        RuleExecutionPlanErrorCategory.UNKNOWN_RULE_CODE,
        RuleExecutionPlanErrorCategory.DUPLICATE_RULE_VERSION_ID,
        RuleExecutionPlanErrorCategory.DUPLICATE_RULE_CODE,
        RuleExecutionPlanErrorCategory.DUPLICATE_RULE_ID,
        RuleExecutionPlanErrorCategory.MISSING_RULE_DEPENDENCY,
        RuleExecutionPlanErrorCategory.UNSUPPORTED_RULE_CONFIGURATION,
        RuleExecutionPlanErrorCategory.INVALID_RULE_EXECUTION_PLAN,
    }
)


def _rule_contract_response(
    trace_id: str,
    category: str,
    *,
    field: str | None = None,
) -> JSONResponse:
    logger.warning(
        "Rule analysis request failed traceId=%s code=RULE_CONTRACT_ERROR category=%s",
        trace_id,
        category,
    )
    field_errors = ()
    if field is not None:
        field_errors = (
            RuleAnalysisFieldError(
                field=field,
                code=category,
                reason="RuleVersion 실행 계약을 만족하지 않습니다.",
            ),
        )
    return _error_response(
        status_code=422,
        code="RULE_CONTRACT_ERROR",
        message=_RULE_CONTRACT_MESSAGE,
        trace_id=trace_id,
        field_errors=field_errors,
    )


def _unsupported_capability_response(trace_id: str, category: str) -> JSONResponse:
    logger.error(
        "Rule analysis request failed traceId=%s code=UNSUPPORTED_RULE_CAPABILITY category=%s",
        trace_id,
        category,
    )
    return _error_response(
        status_code=500,
        code="UNSUPPORTED_RULE_CAPABILITY",
        message=_UNSUPPORTED_CAPABILITY_MESSAGE,
        trace_id=trace_id,
    )


def _internal_error_response(trace_id: str, category: str) -> JSONResponse:
    logger.error(
        "Rule analysis request failed traceId=%s code=INTERNAL_ERROR category=%s",
        trace_id,
        category,
    )
    return _error_response(
        status_code=500,
        code="INTERNAL_ERROR",
        message=_INTERNAL_ERROR_MESSAGE,
        trace_id=trace_id,
    )


def _error_response(
    *,
    status_code: int,
    code: str,
    message: str,
    trace_id: str,
    field_errors: Sequence[RuleAnalysisFieldError] = (),
) -> JSONResponse:
    error = RuleAnalysisErrorResponse(
        code=code,
        message=message,
        traceId=trace_id,
        fieldErrors=tuple(field_errors),
    )
    return JSONResponse(
        status_code=status_code,
        content=error.model_dump(mode="json", by_alias=True),
        headers={TRACE_HEADER_NAME: trace_id},
    )


def _trace_id(request: Request) -> str:
    trace_id = getattr(request.state, "trace_id", None)
    return trace_id if isinstance(trace_id, str) else str(uuid4())


def _validation_field_errors(
    request: Request,
    exc: RequestValidationError,
) -> tuple[RuleAnalysisFieldError, ...]:
    field_errors: list[RuleAnalysisFieldError] = []
    for error in exc.errors():
        error_type = str(error.get("type", ""))
        field, code, reason = _wire_error_details(
            request,
            error.get("loc", ()),
            error_type,
        )
        field_errors.append(RuleAnalysisFieldError(field=field, code=code, reason=reason))
    return tuple(field_errors)


def _wire_error_details(
    request: Request,
    location: object,
    error_type: str,
) -> tuple[str, str, str]:
    if error_type == "json_invalid":
        return "body", "MALFORMED_JSON", "올바른 JSON 형식이어야 합니다."
    field = _field_path(request, location)
    if error_type == "missing":
        return field, "REQUIRED_FIELD", "필수 필드입니다."
    return field, "INVALID_FIELD", "요청 필드 형식을 확인해 주세요."


def _field_path(request: Request, location: object) -> str:
    if not isinstance(location, (tuple, list)):
        return "body"
    location_parts = list(location)
    if location_parts and location_parts[0] == "body":
        location_parts = location_parts[1:]

    safe_parts: list[str] = []
    root_fields = _request_field_aliases(request)
    current_fields: Mapping[str, str] | None = root_fields
    array_item_fields: Mapping[str, str] | None = None
    for part in location_parts:
        if type(part) is int:
            if part < 0 or array_item_fields is None or not safe_parts:
                break
            safe_parts[-1] = f"{safe_parts[-1]}[{part}]"
            current_fields = array_item_fields
            array_item_fields = None
            continue
        if not isinstance(part, str) or current_fields is None:
            break

        alias = current_fields.get(part)
        if alias is None:
            break
        safe_parts.append(alias)

        if current_fields is root_fields and alias == "transaction":
            current_fields = _TRANSACTION_FIELD_ALIASES
        elif current_fields is root_fields and alias == "behaviorEvents":
            current_fields = None
            array_item_fields = _BEHAVIOR_EVENT_FIELD_ALIASES
        elif current_fields is root_fields and alias == "ruleVersions":
            current_fields = None
            array_item_fields = _RULE_VERSION_FIELD_ALIASES
        elif current_fields is root_fields and alias == "externalRisk":
            current_fields = _EXTERNAL_RISK_FIELD_ALIASES
        elif current_fields is _EXTERNAL_RISK_FIELD_ALIASES and alias == "matches":
            current_fields = None
            array_item_fields = _EXTERNAL_RISK_MATCH_FIELD_ALIASES
        else:
            current_fields = None
            array_item_fields = None
    return ".".join(safe_parts) if safe_parts else "body"


def _request_field_aliases(request: Request) -> Mapping[str, str]:
    route = request.scope.get("route")
    if getattr(route, "name", None) == "analyze_rule_v2":
        return _REQUEST_V2_FIELD_ALIASES
    return _REQUEST_FIELD_ALIASES


def _model_field_aliases(model: type[BaseModel]) -> Mapping[str, str]:
    aliases: dict[str, str] = {}
    for field_name, field_info in model.model_fields.items():
        alias = field_info.alias if isinstance(field_info.alias, str) else field_name
        aliases[field_name] = alias
        aliases[alias] = alias
    return MappingProxyType(aliases)


_REQUEST_FIELD_ALIASES = _model_field_aliases(RuleAnalysisRequest)
_REQUEST_V2_FIELD_ALIASES = _model_field_aliases(RuleAnalysisRequestV2)
_TRANSACTION_FIELD_ALIASES = _model_field_aliases(RuleTransactionSnapshotRequest)
_BEHAVIOR_EVENT_FIELD_ALIASES = _model_field_aliases(RuleBehaviorEventSnapshotRequest)
_RULE_VERSION_FIELD_ALIASES = _model_field_aliases(RuleVersionSnapshotRequest)
_EXTERNAL_RISK_FIELD_ALIASES = _model_field_aliases(ExternalRiskSnapshotRequest)
_EXTERNAL_RISK_MATCH_FIELD_ALIASES = _model_field_aliases(ExternalRiskMatchRequest)
