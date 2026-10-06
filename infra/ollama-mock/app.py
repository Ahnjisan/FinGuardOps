"""Isolated Ollama wire-protocol fixture for the official Keycloak Gate.

This is never a model-quality measurement. It returns only fixed synthetic text.
"""

import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from threading import Lock

MODEL = "qwen3.5:4b"
DIGEST = "a" * 64
QUANTIZATION = "Q4_K_M"
RAW_REFUSAL_MARKER = "SYNTHETIC_PROVIDER_RAW_DO_NOT_EXPOSE"
# The Run case first stores a fallback; one later identity read then diverges
# from generation so the Backend stores a final response-contract failure.
_state_lock = Lock()
_first_chat_pending = True
_alternate_identity_pending = False


class Handler(BaseHTTPRequestHandler):
    def log_message(self, _format: str, *_args: object) -> None:
        return

    def _reply(self, status: int, body: dict) -> None:
        encoded = json.dumps(body, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(encoded)))
        self.end_headers()
        self.wfile.write(encoded)

    def do_GET(self) -> None:  # noqa: N802
        if self.path == "/api/tags":
            global _alternate_identity_pending
            with _state_lock:
                alternate = _alternate_identity_pending
                _alternate_identity_pending = False
            self._reply(200, {"models": [{"name": MODEL,
                                         "digest": "b" * 64 if alternate else DIGEST,
                                         "details": {"quantization_level": QUANTIZATION}}]})
        elif self.path == "/health":
            self._reply(200, {"status": "UP"})
        else:
            self._reply(404, {"error": "not found"})

    def do_POST(self) -> None:  # noqa: N802
        if self.path != "/api/chat":
            self._reply(404, {"error": "not found"})
            return
        try:
            size = int(self.headers.get("Content-Length", "0"))
            if size <= 0 or size > 16384:
                raise ValueError()
            request = json.loads(self.rfile.read(size))
            if request.get("model") != MODEL or request.get("stream") is not False:
                raise ValueError()
            prompt = request["messages"][1]["content"]
            projection = json.loads(prompt.split("입력: ", 1)[1])
            reasons = projection["ruleEvidence"]
            if not reasons or len(reasons) > 20:
                raise ValueError()
            body = {
                "summary": projection["safeSummary"],
                "keyReasons": [
                    {"reasonCode": item["reasonCode"],
                     "description": item["description"]}
                    for item in projection["safeKeyReasons"]
                ],
                "investigationChecklist": [projection["allowedChecklist"][0]],
            }
        except (ValueError, KeyError, IndexError, TypeError, json.JSONDecodeError):
            self._reply(400, {"error": "invalid request"})
            return
        global _first_chat_pending, _alternate_identity_pending
        with _state_lock:
            reject = _first_chat_pending
            if reject:
                _first_chat_pending = False
                _alternate_identity_pending = True
        if reject:
            self._reply(200, {"message": {"content": RAW_REFUSAL_MARKER},
                              "prompt_eval_count": 40, "eval_count": 35})
            return
        self._reply(200, {"message": {"content": json.dumps(body, ensure_ascii=False)},
                          "prompt_eval_count": 40, "eval_count": 35})


if __name__ == "__main__":
    ThreadingHTTPServer(("0.0.0.0", 11434), Handler).serve_forever()
