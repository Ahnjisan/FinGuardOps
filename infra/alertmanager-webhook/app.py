"""Bounded in-memory webhook fixture for local Alertmanager validation."""

from __future__ import annotations

import json
import re
import signal
import threading
from collections import deque
from datetime import datetime, timedelta, timezone
from decimal import Decimal, InvalidOperation
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from time import time_ns
from typing import Any

HOST = "0.0.0.0"
PORT = 8080
REQUEST_TIMEOUT_SECONDS = 5
MAX_REQUEST_BYTES = 256 * 1024
MAX_ALERTS = 16
MAX_EVENTS = 256
HEALTH_PATH = "/health"
ALERTS_PATH = "/api/v1/alerts"
EVENTS_PATH = "/events"
RESET_PATH = "/events/reset"
RECEIVER_NAME = "local-webhook"
RUNBOOK_URL = (
    "https://github.com/Ahnjisan/FinGuardOps/blob/main/"
    "docs/09-deployment/prometheus-local-scrape-runbook.md#prometheus-alert-response"
)
ALERT_NAMES = {
    "FinGuardOpsTransactionTerminalFailureRatioWarning": (
        "warning",
        "Transaction terminal failure ratio warning",
        "transaction terminal failure ratio",
    ),
    "FinGuardOpsTransactionTerminalFailureRatioCritical": (
        "critical",
        "Transaction terminal failure ratio critical",
        "transaction terminal failure ratio",
    ),
    "FinGuardOpsExternalRiskFailureRatioWarning": (
        "warning",
        "External Risk failure ratio warning",
        "External Risk failure ratio",
    ),
    "FinGuardOpsExternalRiskFailureRatioCritical": (
        "critical",
        "External Risk failure ratio critical",
        "External Risk failure ratio",
    ),
    "FinGuardOpsRuleAnalysisFailureRatioWarning": (
        "warning",
        "Rule Analysis failure ratio warning",
        "Rule Analysis failure ratio",
    ),
    "FinGuardOpsRuleAnalysisFailureRatioCritical": (
        "critical",
        "Rule Analysis failure ratio critical",
        "Rule Analysis failure ratio",
    ),
}
TOP_LEVEL_FIELDS = {
    "version",
    "groupKey",
    "truncatedAlerts",
    "status",
    "receiver",
    "groupLabels",
    "commonLabels",
    "commonAnnotations",
    "routeLabels",
    "externalURL",
    "notification_reason",
    "alerts",
}
ALERT_FIELDS = {
    "status",
    "labels",
    "annotations",
    "startsAt",
    "endsAt",
    "generatorURL",
    "fingerprint",
}
LABEL_FIELDS = {"alertname", "service", "severity"}
ANNOTATION_FIELDS = {"summary", "description", "runbook_url"}
SERVICE_PATTERN = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,63}\Z")
GROUP_KEY_PATTERN = re.compile(r'[A-Za-z0-9{}:/=,._"\\ -]{1,512}\Z')
FINGERPRINT_PATTERN = re.compile(r"[0-9a-f]{1,64}\Z")
RATIO_PATTERN = re.compile(r"[01]\.[0-9]{3}\Z")
NOTIFICATION_REASON_PATTERN = re.compile(r"[A-Za-z0-9 _-]{1,64}\Z")
RFC3339_PATTERN = re.compile(
    r"(?P<year>[0-9]{4})-(?P<month>[0-9]{2})-(?P<day>[0-9]{2})"
    r"T(?P<hour>[0-9]{2}):(?P<minute>[0-9]{2}):(?P<second>[0-9]{2})"
    r"(?:\.(?P<fraction>[0-9]{1,9}))?"
    r"(?P<timezone>Z|(?P<offset_sign>[+-])(?P<offset_hour>[0-9]{2}):"
    r"(?P<offset_minute>[0-9]{2}))\Z"
)
ZERO_TIMESTAMP = "0001-01-01T00:00:00Z"
ZERO_DECIMAL = Decimal(0)
ONE_DECIMAL = Decimal(1)

TimestampValue = tuple[datetime, int]


class ValidationError(ValueError):
    """Payload does not match the local Alertmanager contract."""


class EventStore:
    """Thread-safe bounded store with a monotonic sequence across resets."""

    def __init__(self) -> None:
        self._events: deque[dict[str, Any]] = deque(maxlen=MAX_EVENTS)
        self._next_sequence = 1
        self._lock = threading.Lock()

    def append(self, event: dict[str, Any]) -> dict[str, Any]:
        with self._lock:
            stored = {"sequence": self._next_sequence, **event}
            self._next_sequence += 1
            self._events.append(stored)
            return stored

    def snapshot(self) -> list[dict[str, Any]]:
        with self._lock:
            return list(self._events)

    def reset(self) -> int:
        with self._lock:
            removed = len(self._events)
            self._events.clear()
            return removed


EVENTS = EventStore()


def _require_exact_fields(
    value: Any, fields: set[str], location: str
) -> dict[str, Any]:
    if not isinstance(value, dict) or set(value) != fields:
        raise ValidationError(f"invalid {location} fields")
    return value


def _require_string(value: Any, location: str, *, allow_empty: bool = False) -> str:
    if not isinstance(value, str) or (not allow_empty and not value):
        raise ValidationError(f"invalid {location}")
    return value


def _validate_timestamp(value: Any, location: str) -> tuple[str, TimestampValue]:
    timestamp = _require_string(value, location)
    match = RFC3339_PATTERN.fullmatch(timestamp)
    if match is None:
        raise ValidationError(f"invalid {location}")

    offset_hour = int(match["offset_hour"] or 0)
    offset_minute = int(match["offset_minute"] or 0)
    if offset_hour > 23 or offset_minute > 59:
        raise ValidationError(f"invalid {location}")

    offset = timedelta(hours=offset_hour, minutes=offset_minute)
    if match["offset_sign"] == "-":
        offset = -offset
    parsed_timezone = timezone.utc if match["timezone"] == "Z" else timezone(offset)
    fraction = (match["fraction"] or "").ljust(9, "0")
    nanosecond = int(fraction or 0)
    try:
        parsed = datetime(
            int(match["year"]),
            int(match["month"]),
            int(match["day"]),
            int(match["hour"]),
            int(match["minute"]),
            int(match["second"]),
            nanosecond // 1000,
            tzinfo=parsed_timezone,
        ).astimezone(timezone.utc)
    except (OverflowError, ValueError) as error:
        raise ValidationError(f"invalid {location}") from error
    if parsed.tzinfo is None or parsed.utcoffset() is None:  # defensive invariant
        raise ValidationError(f"invalid {location}")
    return timestamp, (parsed, nanosecond % 1000)


def _capture_validation_time() -> TimestampValue:
    epoch_nanoseconds = time_ns()
    seconds, nanosecond = divmod(epoch_nanoseconds, 1_000_000_000)
    captured = datetime.fromtimestamp(seconds, timezone.utc).replace(
        microsecond=nanosecond // 1000
    )
    return captured, nanosecond % 1000


def _format_received_at(validation_time: TimestampValue) -> str:
    return validation_time[0].isoformat(timespec="milliseconds").replace("+00:00", "Z")


def _validate_label_values(labels: dict[str, str], location: str) -> None:
    if any(not isinstance(value, str) or not value for value in labels.values()):
        raise ValidationError(f"invalid {location} values")
    if "alertname" in labels and labels["alertname"] not in ALERT_NAMES:
        raise ValidationError(f"invalid {location} alertname")
    if "service" in labels and not SERVICE_PATTERN.fullmatch(labels["service"]):
        raise ValidationError(f"invalid {location} service")
    if "severity" in labels and labels["severity"] not in {"warning", "critical"}:
        raise ValidationError(f"invalid {location} severity")


def _validate_annotations(
    value: Any, alertname: str, service: str, location: str
) -> dict[str, str]:
    annotations = _require_exact_fields(value, ANNOTATION_FIELDS, location)
    if any(not isinstance(item, str) or not item for item in annotations.values()):
        raise ValidationError(f"invalid {location} values")
    severity, summary, description_name = ALERT_NAMES[alertname]
    if annotations["summary"] != summary or annotations["runbook_url"] != RUNBOOK_URL:
        raise ValidationError(f"invalid {location} static values")
    description_prefix = f"Service {service} {description_name} is "
    description = annotations["description"]
    if not description.startswith(description_prefix) or not description.endswith("."):
        raise ValidationError(f"invalid {location} description")
    ratio = description[len(description_prefix) : -1]
    if not RATIO_PATTERN.fullmatch(ratio):
        raise ValidationError(f"invalid {location} ratio")
    try:
        decimal_ratio = Decimal(ratio)
    except InvalidOperation as error:
        raise ValidationError(f"invalid {location} ratio") from error
    if (
        not decimal_ratio.is_finite()
        or decimal_ratio < ZERO_DECIMAL
        or decimal_ratio > ONE_DECIMAL
    ):
        raise ValidationError(f"invalid {location} ratio")
    if severity not in {"warning", "critical"}:  # defensive consistency check
        raise ValidationError(f"invalid {location} severity")
    return annotations


def _normalize_alert(
    value: Any, index: int, validation_time: TimestampValue
) -> dict[str, Any]:
    alert = _require_exact_fields(value, ALERT_FIELDS, f"alerts[{index}]")
    status = alert["status"]
    if status not in {"firing", "resolved"}:
        raise ValidationError(f"invalid alerts[{index}].status")
    labels = _require_exact_fields(
        alert["labels"], LABEL_FIELDS, f"alerts[{index}].labels"
    )
    _validate_label_values(labels, f"alerts[{index}].labels")
    alertname = labels["alertname"]
    service = labels["service"]
    expected_severity = ALERT_NAMES[alertname][0]
    if labels["severity"] != expected_severity:
        raise ValidationError(f"invalid alerts[{index}].labels severity")
    annotations = _validate_annotations(
        alert["annotations"], alertname, service, f"alerts[{index}].annotations"
    )
    starts_at, parsed_starts_at = _validate_timestamp(
        alert["startsAt"], f"alerts[{index}].startsAt"
    )
    ends_at, parsed_ends_at = _validate_timestamp(
        alert["endsAt"], f"alerts[{index}].endsAt"
    )
    if status == "firing":
        if ends_at != ZERO_TIMESTAMP:
            if parsed_ends_at <= validation_time:
                raise ValidationError(f"invalid alerts[{index}].endsAt")
            if parsed_starts_at > parsed_ends_at:
                raise ValidationError(f"invalid alerts[{index}] timestamp order")
    elif ends_at == ZERO_TIMESTAMP or parsed_ends_at < parsed_starts_at:
        raise ValidationError(f"invalid alerts[{index}] timestamp order")
    _require_string(
        alert["generatorURL"], f"alerts[{index}].generatorURL", allow_empty=True
    )
    fingerprint = _require_string(alert["fingerprint"], f"alerts[{index}].fingerprint")
    if not FINGERPRINT_PATTERN.fullmatch(fingerprint):
        raise ValidationError(f"invalid alerts[{index}].fingerprint")
    return {
        "status": status,
        "labels": dict(sorted(labels.items())),
        "annotations": dict(sorted(annotations.items())),
        "startsAt": starts_at,
        "endsAt": ends_at,
        "fingerprint": fingerprint,
    }


def normalize_payload(
    value: Any, received_at: str, validation_time: TimestampValue
) -> dict[str, Any]:
    payload = _require_exact_fields(value, TOP_LEVEL_FIELDS, "payload")
    if payload["version"] != "4":
        raise ValidationError("invalid version")
    status = payload["status"]
    if status not in {"firing", "resolved"}:
        raise ValidationError("invalid status")
    if payload["receiver"] != RECEIVER_NAME:
        raise ValidationError("invalid receiver")
    group_key = _require_string(payload["groupKey"], "groupKey")
    if not GROUP_KEY_PATTERN.fullmatch(group_key):
        raise ValidationError("invalid groupKey")
    _require_string(payload["externalURL"], "externalURL", allow_empty=True)
    notification_reason = _require_string(
        payload["notification_reason"], "notification_reason"
    )
    if not NOTIFICATION_REASON_PATTERN.fullmatch(notification_reason):
        raise ValidationError("invalid notification_reason")
    if payload["routeLabels"] != {}:
        raise ValidationError("invalid routeLabels")
    truncated_alerts = payload["truncatedAlerts"]
    if not isinstance(truncated_alerts, int) or isinstance(truncated_alerts, bool):
        raise ValidationError("invalid truncatedAlerts")
    if truncated_alerts < 0:
        raise ValidationError("invalid truncatedAlerts")

    group_labels = _require_exact_fields(
        payload["groupLabels"], {"alertname", "service"}, "groupLabels"
    )
    common_labels = _require_exact_fields(
        payload["commonLabels"], LABEL_FIELDS, "commonLabels"
    )
    _validate_label_values(group_labels, "groupLabels")
    _validate_label_values(common_labels, "commonLabels")
    if any(group_labels[field] != common_labels[field] for field in group_labels):
        raise ValidationError("groupLabels and commonLabels differ")

    alerts = payload["alerts"]
    if not isinstance(alerts, list) or not alerts or len(alerts) > MAX_ALERTS:
        raise ValidationError("invalid alerts count")
    normalized_alerts = [
        _normalize_alert(alert, index, validation_time)
        for index, alert in enumerate(alerts)
    ]
    first_labels = normalized_alerts[0]["labels"]
    expected_status = (
        "firing"
        if any(alert["status"] == "firing" for alert in normalized_alerts)
        else "resolved"
    )
    if status != expected_status:
        raise ValidationError("invalid aggregate status")
    if any(alert["labels"] != first_labels for alert in normalized_alerts):
        raise ValidationError("group contains different alert labels")
    if first_labels != dict(sorted(common_labels.items())):
        raise ValidationError("commonLabels and alert labels differ")
    common_annotations = _validate_annotations(
        payload["commonAnnotations"],
        first_labels["alertname"],
        first_labels["service"],
        "commonAnnotations",
    )
    if any(
        alert["annotations"] != dict(sorted(common_annotations.items()))
        for alert in normalized_alerts
    ):
        raise ValidationError("commonAnnotations and alert annotations differ")

    return {
        "receivedAt": received_at,
        "status": status,
        "receiver": RECEIVER_NAME,
        "groupKey": group_key,
        "groupLabels": dict(sorted(group_labels.items())),
        "commonLabels": dict(sorted(common_labels.items())),
        "commonAnnotations": dict(sorted(common_annotations.items())),
        "alerts": normalized_alerts,
        "truncatedAlerts": truncated_alerts,
    }


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def __getattr__(self, name: str) -> Any:
        if name.startswith("do_"):
            return self._method_not_allowed
        raise AttributeError(name)

    def setup(self) -> None:
        super().setup()
        self.connection.settimeout(REQUEST_TIMEOUT_SECONDS)

    def do_GET(self) -> None:
        if self.path == HEALTH_PATH:
            self._json(
                HTTPStatus.OK, {"service": "alertmanager-webhook", "status": "UP"}
            )
        elif self.path == EVENTS_PATH:
            events = EVENTS.snapshot()
            self._json(HTTPStatus.OK, {"count": len(events), "events": events})
        else:
            self._json(HTTPStatus.NOT_FOUND, {"status": "NOT_FOUND"})

    def do_POST(self) -> None:
        if self.path == RESET_PATH:
            self._reset_events()
        elif self.path == ALERTS_PATH:
            self._receive_alerts()
        else:
            self._json(HTTPStatus.NOT_FOUND, {"status": "NOT_FOUND"})

    def do_DELETE(self) -> None:
        self._method_not_allowed()

    def do_HEAD(self) -> None:
        self._method_not_allowed()

    def do_OPTIONS(self) -> None:
        self._method_not_allowed()

    def do_PATCH(self) -> None:
        self._method_not_allowed()

    def do_PUT(self) -> None:
        self._method_not_allowed()

    def do_TRACE(self) -> None:
        self._method_not_allowed()

    def do_CONNECT(self) -> None:
        self._method_not_allowed()

    def _method_not_allowed(self) -> None:
        self._json(HTTPStatus.METHOD_NOT_ALLOWED, {"status": "METHOD_NOT_ALLOWED"})

    def _reset_events(self) -> None:
        if self.headers.get("Transfer-Encoding"):
            self._json(HTTPStatus.BAD_REQUEST, {"status": "INVALID_REQUEST"})
            return
        lengths = self.headers.get_all("Content-Length", [])
        if len(lengths) > 1:
            self._json(HTTPStatus.BAD_REQUEST, {"status": "INVALID_REQUEST"})
            return
        try:
            length = int(lengths[0]) if lengths else 0
        except ValueError:
            self._json(HTTPStatus.BAD_REQUEST, {"status": "INVALID_REQUEST"})
            return
        if length < 0:
            self._json(HTTPStatus.BAD_REQUEST, {"status": "INVALID_REQUEST"})
            return
        if length > MAX_REQUEST_BYTES:
            self._json(
                HTTPStatus.REQUEST_ENTITY_TOO_LARGE, {"status": "PAYLOAD_TOO_LARGE"}
            )
            return
        if length != 0:
            self._json(HTTPStatus.BAD_REQUEST, {"status": "INVALID_REQUEST"})
            return
        removed = EVENTS.reset()
        self._json(
            HTTPStatus.OK,
            {
                "removed": removed,
                "sequencePolicy": "monotonic-across-reset",
                "status": "RESET",
            },
        )

    def _receive_alerts(self) -> None:
        validation_time = _capture_validation_time()
        content_type = self.headers.get("Content-Type", "")
        if content_type.split(";", 1)[0].strip().lower() != "application/json":
            self._json(
                HTTPStatus.UNSUPPORTED_MEDIA_TYPE, {"status": "UNSUPPORTED_MEDIA_TYPE"}
            )
            return
        body_status, body = self._request_body()
        if body is None:
            self._json(
                body_status,
                {"status": HTTPStatus(body_status).phrase.upper().replace(" ", "_")},
            )
            return
        try:
            value = json.loads(body)
            received_at = _format_received_at(validation_time)
            event = normalize_payload(value, received_at, validation_time)
        except (UnicodeDecodeError, json.JSONDecodeError):
            self._json(HTTPStatus.BAD_REQUEST, {"status": "INVALID_JSON"})
            return
        except ValidationError:
            self._json(HTTPStatus.BAD_REQUEST, {"status": "INVALID_PAYLOAD"})
            return
        stored = EVENTS.append(event)
        self._json(
            HTTPStatus.OK, {"sequence": stored["sequence"], "status": "ACCEPTED"}
        )

    def _request_body(self) -> tuple[HTTPStatus, bytes | None]:
        if self.headers.get("Transfer-Encoding"):
            return HTTPStatus.BAD_REQUEST, None
        lengths = self.headers.get_all("Content-Length", [])
        if not lengths:
            return HTTPStatus.LENGTH_REQUIRED, None
        if len(lengths) != 1:
            return HTTPStatus.BAD_REQUEST, None
        try:
            length = int(lengths[0])
        except ValueError:
            return HTTPStatus.BAD_REQUEST, None
        if length < 0:
            return HTTPStatus.BAD_REQUEST, None
        if length > MAX_REQUEST_BYTES:
            return HTTPStatus.REQUEST_ENTITY_TOO_LARGE, None
        try:
            body = self.rfile.read(length)
        except TimeoutError:
            return HTTPStatus.REQUEST_TIMEOUT, None
        if len(body) != length:
            return HTTPStatus.BAD_REQUEST, None
        return HTTPStatus.OK, body

    def _json(self, status: HTTPStatus, payload: dict[str, Any]) -> None:
        body = json.dumps(
            payload, ensure_ascii=True, separators=(",", ":"), sort_keys=True
        ).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("Connection", "close")
        self.end_headers()
        if self.command != "HEAD":
            try:
                self.wfile.write(body)
            except (TimeoutError, BrokenPipeError, ConnectionResetError):
                return

    def log_message(self, format: str, *args: object) -> None:
        return


class Server(ThreadingHTTPServer):
    daemon_threads = False
    block_on_close = True
    allow_reuse_address = True

    def handle_error(self, request: Any, client_address: Any) -> None:
        return


def main() -> None:
    with Server((HOST, PORT), Handler) as server:

        def request_shutdown(signum: int, frame: Any) -> None:
            threading.Thread(
                target=server.shutdown, name="server-shutdown", daemon=True
            ).start()

        signal.signal(signal.SIGINT, request_shutdown)
        signal.signal(signal.SIGTERM, request_shutdown)
        server.serve_forever(poll_interval=0.2)


if __name__ == "__main__":
    main()
