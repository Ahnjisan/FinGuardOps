"""Opt-in local OTLP tracing and allowlisted application log export."""

import logging
import os

from opentelemetry import trace
from opentelemetry.exporter.otlp.proto.http._log_exporter import OTLPLogExporter
from opentelemetry.exporter.otlp.proto.http.trace_exporter import OTLPSpanExporter
from opentelemetry.propagate import extract
from opentelemetry.sdk._logs import LoggerProvider, LoggingHandler
from opentelemetry.sdk._logs.export import BatchLogRecordProcessor
from opentelemetry.sdk.resources import Resource
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import BatchSpanProcessor
from opentelemetry.trace import SpanKind, Status, StatusCode
from starlette.types import ASGIApp, Receive, Scope, Send


def configure_telemetry() -> bool:
    """Keep exporters absent in normal Compose and tests."""
    if os.getenv("FINGUARDOPS_AI_OBSERVABILITY_ENABLED") != "true":
        return False
    origin = os.getenv("FINGUARDOPS_AI_OTLP_ENDPOINT", "http://otel-collector:4318").rstrip("/")
    resource = Resource.create(
        {"service.name": "ai-service", "deployment.environment.name": "local"}
    )
    traces = TracerProvider(resource=resource)
    traces.add_span_processor(
        BatchSpanProcessor(
            OTLPSpanExporter(endpoint=f"{origin}/v1/traces", timeout=2),
            max_queue_size=256,
            max_export_batch_size=32,
            schedule_delay_millis=1000,
            export_timeout_millis=2000,
        )
    )
    trace.set_tracer_provider(traces)
    logs = LoggerProvider(resource=resource)
    logs.add_log_record_processor(
        BatchLogRecordProcessor(
            OTLPLogExporter(endpoint=f"{origin}/v1/logs", timeout=2),
            max_queue_size=256,
            max_export_batch_size=32,
            schedule_delay_millis=1000,
            export_timeout_millis=2000,
        )
    )
    # Export only our allowlisted messages, not Uvicorn access lines or dependency exceptions.
    application_logger = logging.getLogger("finguardops_ai")
    application_logger.addHandler(LoggingHandler(level=logging.INFO, logger_provider=logs))
    application_logger.setLevel(logging.INFO)
    return True


class TelemetryMiddleware:
    def __init__(self, app: ASGIApp) -> None:
        self.app = app
        self.tracer = trace.get_tracer("finguardops.ai-service")
        self.logger = logging.getLogger("finguardops_ai.telemetry")

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return
        if scope.get("path") == "/api/health":
            await self.app(scope, receive, send)
            return
        headers = [
            (key.decode("latin-1"), value.decode("latin-1"))
            for key, value in scope.get("headers", [])
        ]
        parent = extract(headers, getter=_HeaderGetter())
        path = scope.get("path", "")
        # A fixed operation name prevents IDs in the path from becoming span names.
        operation = "http.server ai-service"
        status = 500
        with self.tracer.start_as_current_span(
            operation, context=parent, kind=SpanKind.SERVER
        ) as span:

            async def send_observed(message: dict) -> None:
                nonlocal status
                if message["type"] == "http.response.start":
                    status = message["status"]
                await send(message)

            try:
                await self.app(scope, receive, send_observed)
            except Exception:
                span.set_status(Status(StatusCode.ERROR, "request_failed"))
                raise
            finally:
                safe_paths = {
                    "/api/v1/rule-analysis",
                    "/api/v2/rule-analysis",
                    "/api/v1/ai-reports",
                    "/api/v1/ai-reports/model",
                }
                route = scope.get("route")
                template = path if path in safe_paths else getattr(route, "path", "unmatched")
                if not isinstance(template, str):
                    template = "unmatched"
                span.set_attribute("http.route", template)
                span.set_attribute("http.response.status_code", status)
                self.logger.info(
                    "event=ai_http_completed route=%s status=%d otelTraceId=%s",
                    template,
                    status,
                    span.get_span_context().trace_id.to_bytes(16, "big").hex(),
                )


class _HeaderGetter:
    def get(self, carrier: list[tuple[str, str]], key: str) -> list[str] | None:
        values = [value for name, value in carrier if name.lower() == key.lower()]
        return values or None

    def keys(self, carrier: list[tuple[str, str]]) -> list[str]:
        return [name for name, _ in carrier]
