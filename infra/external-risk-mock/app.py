"""Local Docker Compose fixture for the existing External Risk HTTP contract."""

import json
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
        request = self._request_json()
        if request is None:
            self._json(400, {"status": "INVALID_REQUEST"})
            return
        self._json(
            200,
            {
                "providerCode": "PROVIDER_V1",
                "providerAsOf": request["evaluationCutoffAt"],
                "matches": [],
            },
        )

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
        self.wfile.write(body)

    def log_message(self, format: str, *args: object) -> None:
        return


if __name__ == "__main__":
    ThreadingHTTPServer((HOST, PORT), Handler).serve_forever()
