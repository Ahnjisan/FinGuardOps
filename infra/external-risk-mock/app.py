"""Local Docker Compose fixture for the existing External Risk HTTP contract."""

import json
import os
import time
from datetime import datetime, timedelta
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HOST = "127.0.0.1"
PORT = 8001
LOOKUP_PATH = "/v1/external-risk/lookup"
HEALTH_PATH = "/health"
REQUEST_FIELDS = {
    "transactionType",
    "evaluationCutoffAt",
    "externalCustomerRef",
    "senderAccountRef",
    "recipientAccountRef",
    "deviceRef",
    "traceId",
}
MAX_REQUEST_BYTES = 65_536
LOOKUP_RECEIVED_MARKER = "FINGUARDOPS_EXTERNAL_RISK_LOOKUP_RECEIVED"
SCN003_FIXTURE_ENABLED = os.environ.get("FINGUARDOPS_SCN003_FIXTURE_ENABLED") == "true"


def scn003_response(request: dict[str, object]) -> tuple[int, dict[str, object]]:
    """Deterministic synthetic responses only in the dedicated issue-382 overlay."""
    recipient = request["recipientAccountRef"]
    sender = request["senderAccountRef"]
    if not isinstance(recipient, str) or not recipient.startswith("issue382-synthetic-"):
        return 200, {"providerCode": "PROVIDER_V1",
                     "providerAsOf": request["evaluationCutoffAt"], "matches": []}
    scenario = recipient.removeprefix("issue382-synthetic-").split("-", 1)[0]
    if scenario == "unavailable":
        return 503, {"status": "UNAVAILABLE"}
    if scenario == "timeout":
        time.sleep(4)
    cutoff = datetime.fromisoformat(str(request["evaluationCutoffAt"]).replace("Z", "+00:00"))
    as_of = cutoff
    if scenario == "boundary":
        as_of -= timedelta(hours=24)
    elif scenario == "stale":
        as_of -= timedelta(hours=24, microseconds=1)
    elif scenario == "future":
        as_of += timedelta(microseconds=1)
    matches = []
    if scenario in {"risk", "boundary", "stale", "future", "wrongcode"}:
        matches.append({"subjectType": "RECIPIENT_ACCOUNT", "riskType": "SUSPICIOUS_ACCOUNT",
                        "reasonCode": "SUSPICIOUS_RECIPIENT_ACCOUNT"})
    elif scenario == "senderonly" and isinstance(sender, str):
        matches.append({"subjectType": "SENDER_ACCOUNT", "riskType": "SUSPICIOUS_ACCOUNT",
                        "reasonCode": "SUSPICIOUS_SENDER_ACCOUNT"})
    elif scenario == "contradictory":
        matches.append({"subjectType": "RECIPIENT_ACCOUNT", "riskType": "RISK_DEVICE",
                        "reasonCode": "RISK_DEVICE"})
    return 200, {"providerCode": "WRONG_PROVIDER" if scenario == "wrongcode" else "PROVIDER_V1",
                 "providerAsOf": as_of.isoformat().replace("+00:00", "Z"), "matches": matches}


class Handler(BaseHTTPRequestHandler):
    def do_GET(self) -> None:  # noqa: N802 - stdlib handler contract
        if self.path != HEALTH_PATH:
            self._json(404, {"status": "NOT_FOUND"})
            return
        self._json(200, {"status": "UP", "service": "external-risk-mock"})

    def do_POST(self) -> None:  # noqa: N802 - stdlib handler contract
        if self.path != LOOKUP_PATH:
            self._json(404, {"status": "NOT_FOUND"})
            return
        print(LOOKUP_RECEIVED_MARKER, flush=True)
        request = self._request_json()
        if request is None:
            self._json(400, {"status": "INVALID_REQUEST"})
            return
        if SCN003_FIXTURE_ENABLED:
            status, payload = scn003_response(request)
            self._json(status, payload)
        else:
            self._json(200, {"providerCode": "PROVIDER_V1",
                             "providerAsOf": request["evaluationCutoffAt"], "matches": []})

    def _request_json(self) -> dict[str, object] | None:
        try:
            content_type = self.headers.get("Content-Type", "")
            if content_type.split(";", 1)[0].strip().lower() != "application/json":
                return None
            body = self._request_body()
            if body is None:
                return None
            value = json.loads(body)
        except (UnicodeDecodeError, ValueError, json.JSONDecodeError):
            return None
        if not isinstance(value, dict) or set(value) != REQUEST_FIELDS:
            return None
        required_strings = (
            "transactionType",
            "evaluationCutoffAt",
            "externalCustomerRef",
            "senderAccountRef",
            "traceId",
        )
        if any(
            not isinstance(value.get(field), str) or not value[field] for field in required_strings
        ):
            return None
        if any(
            value.get(field) is not None and not isinstance(value[field], str)
            for field in ("recipientAccountRef", "deviceRef")
        ):
            return None
        return value

    def _request_body(self) -> bytes | None:
        content_length = self.headers.get("Content-Length")
        transfer_encoding = self.headers.get("Transfer-Encoding", "").lower()
        if transfer_encoding:
            if transfer_encoding != "chunked" or content_length is not None:
                return None
            return self._chunked_body()
        try:
            length = int(content_length or "-1")
        except ValueError:
            return None
        if length < 1 or length > MAX_REQUEST_BYTES:
            return None
        return self.rfile.read(length)

    def _chunked_body(self) -> bytes | None:
        body = bytearray()
        while True:
            size_line = self.rfile.readline(128)
            if not size_line.endswith(b"\r\n") or b";" in size_line:
                return None
            try:
                size = int(size_line[:-2], 16)
            except ValueError:
                return None
            if size < 0 or len(body) + size > MAX_REQUEST_BYTES:
                return None
            if size == 0:
                return body if self.rfile.readline(2) == b"\r\n" else None
            chunk = self.rfile.read(size)
            if len(chunk) != size or self.rfile.read(2) != b"\r\n":
                return None
            body.extend(chunk)

    def _json(self, status: int, payload: dict[str, object]) -> None:
        body = json.dumps(payload, separators=(",", ":")).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        try:
            self.wfile.write(body)
        except BrokenPipeError:
            pass  # A timeout probe has already closed its local HTTP connection.

    def log_message(self, format: str, *args: object) -> None:
        return


if __name__ == "__main__":
    ThreadingHTTPServer((HOST, PORT), Handler).serve_forever()
