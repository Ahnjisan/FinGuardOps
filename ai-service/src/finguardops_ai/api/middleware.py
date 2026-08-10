"""ASGI boundary checks that must run before Rule analysis body parsing."""

import logging
import re
from uuid import uuid4

from starlette.responses import JSONResponse
from starlette.types import ASGIApp, Message, Receive, Scope, Send

from finguardops_ai.schemas.errors import RuleAnalysisErrorResponse

TRACE_HEADER_NAME = "X-Trace-Id"
MAX_RULE_ANALYSIS_BODY_BYTES = 1_048_576

_TRACE_HEADER_NAME_BYTES = b"x-trace-id"
_TRACE_ID_PATTERN = re.compile(rb"^[A-Za-z0-9][A-Za-z0-9._:-]{7,63}$")
_INVALID_REQUEST_MESSAGE = "Rule 분석 요청 형식을 확인해 주세요."
_PAYLOAD_TOO_LARGE_MESSAGE = "Rule 분석 요청 본문 크기는 1 MiB 이하여야 합니다."
_INTERNAL_ERROR_MESSAGE = "Rule 분석 요청을 처리하는 중 오류가 발생했습니다."

logger = logging.getLogger(__name__)


class RuleAnalysisHttpMiddleware:
    """Validate Trace first, then enforce a byte limit while reading ASGI chunks."""

    def __init__(
        self,
        app: ASGIApp,
        *,
        path: str = "/api/v1/rule-analysis",
        max_body_bytes: int = MAX_RULE_ANALYSIS_BODY_BYTES,
    ) -> None:
        self._app = app
        self._path = path
        self._max_body_bytes = max_body_bytes

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if not self._is_rule_analysis_request(scope):
            await self._app(scope, receive, send)
            return

        trace_id = self._validated_trace_id(scope)
        if trace_id is None:
            local_trace_id = str(uuid4())
            logger.warning(
                "Rule analysis request rejected traceId=%s code=INVALID_REQUEST",
                local_trace_id,
            )
            await _send_error(
                scope,
                receive,
                send,
                status_code=400,
                code="INVALID_REQUEST",
                message=_INVALID_REQUEST_MESSAGE,
                trace_id=local_trace_id,
            )
            return

        scope.setdefault("state", {})["trace_id"] = trace_id
        body = await self._read_limited_body(scope, receive, send, trace_id)
        if body is None:
            return

        response_status: int | None = None

        async def replay_receive() -> Message:
            nonlocal body
            if body is None:
                return {"type": "http.disconnect"}
            replay = body
            body = None
            return {"type": "http.request", "body": replay, "more_body": False}

        async def send_with_trace(message: Message) -> None:
            nonlocal response_status
            if message["type"] == "http.response.start":
                response_status = message["status"]
                headers = [
                    (name, value)
                    for name, value in message.get("headers", [])
                    if name.lower() != _TRACE_HEADER_NAME_BYTES
                ]
                headers.append((_TRACE_HEADER_NAME_BYTES, trace_id.encode("ascii")))
                message = {**message, "headers": headers}
            await send(message)

        try:
            await self._app(scope, replay_receive, send_with_trace)
        except Exception:
            if response_status is not None:
                raise
            logger.error(
                "Rule analysis request failed traceId=%s code=INTERNAL_ERROR category=unexpected",
                trace_id,
            )
            await _send_error(
                scope,
                replay_receive,
                send,
                status_code=500,
                code="INTERNAL_ERROR",
                message=_INTERNAL_ERROR_MESSAGE,
                trace_id=trace_id,
            )
            return
        if response_status is not None:
            logger.info(
                "Rule analysis request completed traceId=%s status=%d",
                trace_id,
                response_status,
            )

    def _is_rule_analysis_request(self, scope: Scope) -> bool:
        return (
            scope["type"] == "http"
            and scope.get("method") == "POST"
            and scope.get("path") == self._path
        )

    @staticmethod
    def _validated_trace_id(scope: Scope) -> str | None:
        values = [
            value
            for name, value in scope.get("headers", [])
            if name.lower() == _TRACE_HEADER_NAME_BYTES
        ]
        if len(values) != 1 or _TRACE_ID_PATTERN.fullmatch(values[0]) is None:
            return None
        return values[0].decode("ascii")

    async def _read_limited_body(
        self,
        scope: Scope,
        receive: Receive,
        send: Send,
        trace_id: str,
    ) -> bytes | None:
        chunks: list[bytes] = []
        received_bytes = 0
        more_body = True

        while more_body:
            message = await receive()
            if message["type"] == "http.disconnect":
                return None
            if message["type"] != "http.request":
                continue

            chunk = message.get("body", b"")
            received_bytes += len(chunk)
            if received_bytes > self._max_body_bytes:
                logger.warning(
                    "Rule analysis request rejected traceId=%s code=PAYLOAD_TOO_LARGE",
                    trace_id,
                )
                await _send_error(
                    scope,
                    receive,
                    send,
                    status_code=413,
                    code="PAYLOAD_TOO_LARGE",
                    message=_PAYLOAD_TOO_LARGE_MESSAGE,
                    trace_id=trace_id,
                )
                return None

            chunks.append(chunk)
            more_body = message.get("more_body", False)

        return b"".join(chunks)


async def _send_error(
    scope: Scope,
    receive: Receive,
    send: Send,
    *,
    status_code: int,
    code: str,
    message: str,
    trace_id: str,
) -> None:
    error = RuleAnalysisErrorResponse(
        code=code,
        message=message,
        traceId=trace_id,
        fieldErrors=(),
    )
    response = JSONResponse(
        status_code=status_code,
        content=error.model_dump(mode="json", by_alias=True),
        headers={TRACE_HEADER_NAME: trace_id},
    )
    await response(scope, receive, send)
