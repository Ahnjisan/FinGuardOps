"""Bounded local Ollama transport. No arbitrary URL or raw response logging."""

import json
import time
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import ProxyHandler, Request, build_opener

from finguardops_ai.core.config import Settings

SYSTEM_PROMPT = (
    "Return only JSON grounded in the supplied RULE codes. "
    "Do not invent events, identities, decisions or risk scores. "
    "A reason-code label is not proof of a particular customer's behavior, device use, "
    "transaction amount, or score change. State that the code was adopted and phrase "
    "follow-up checks as questions rather than confirmed facts."
)
OLLAMA_CHAT_PARAMETERS = {"format": "json", "think": False, "temperature": 0}


class OllamaFailure(Exception):
    def __init__(self, code: str, attempted: bool = True) -> None:
        super().__init__(code)
        self.code = code
        self.attempted = attempted


class OllamaClient:
    def __init__(self, settings: Settings) -> None:
        self.settings = settings
        url = urlsplit(settings.ollama_base_url)
        if (
            url.scheme != "http"
            or not url.hostname
            or url.username
            or url.password
            or url.path not in ("", "/")
        ):
            raise ValueError("Ollama requires a configured local HTTP origin")
        if url.hostname not in {"localhost", "127.0.0.1", "host.docker.internal", "backend"}:
            raise ValueError("Ollama origin must be local")
        self.origin = settings.ollama_base_url.rstrip("/")
        self.opener = build_opener(ProxyHandler({}))

    def _json(self, path: str, data: dict | None = None) -> dict:
        payload = None if data is None else json.dumps(data, separators=(",", ":")).encode("utf-8")
        request = Request(
            self.origin + path,
            data=payload,
            headers={"Content-Type": "application/json"},
            method="GET" if payload is None else "POST",
        )
        try:
            with self.opener.open(
                request, timeout=self.settings.ai_report_timeout_seconds
            ) as response:
                if response.status != 200:
                    raise OllamaFailure("PROVIDER_ERROR")
                raw = response.read(131_073)
                if len(raw) > 131_072:
                    raise OllamaFailure("PROVIDER_ERROR")
                parsed = json.loads(raw)
                if not isinstance(parsed, dict):
                    raise OllamaFailure("PROVIDER_ERROR")
                return parsed
        except TimeoutError as exc:
            raise OllamaFailure("TIMEOUT") from exc
        except HTTPError as exc:
            raise OllamaFailure("PROVIDER_ERROR") from exc
        except URLError as exc:
            if isinstance(exc.reason, TimeoutError):
                raise OllamaFailure("TIMEOUT") from exc
            if isinstance(exc.reason, (ConnectionRefusedError, ConnectionResetError,
                                       ConnectionAbortedError, BrokenPipeError)):
                raise OllamaFailure("CONNECTION_FAILED") from exc
            raise OllamaFailure("PROVIDER_ERROR") from exc
        except (ConnectionRefusedError, ConnectionResetError,
                ConnectionAbortedError, BrokenPipeError) as exc:
            raise OllamaFailure("CONNECTION_FAILED") from exc
        except (OSError, ValueError, json.JSONDecodeError) as exc:
            raise OllamaFailure("PROVIDER_ERROR") from exc

    def model_metadata(self) -> tuple[str, str]:
        try:
            tags = self._json("/api/tags")
        except OllamaFailure as exc:
            if exc.code in {"TIMEOUT", "CONNECTION_FAILED"}:
                raise OllamaFailure(exc.code, attempted=False) from exc
            raise OllamaFailure("MODEL_METADATA_UNAVAILABLE", attempted=False) from exc
        models = tags.get("models")
        if not isinstance(models, list):
            raise OllamaFailure("MODEL_METADATA_UNAVAILABLE", attempted=False)
        match = next(
            (
                item
                for item in models
                if isinstance(item, dict) and item.get("name") == self.settings.ollama_model
            ),
            None,
        )
        if match is None or not isinstance(match.get("digest"), str):
            raise OllamaFailure("MODEL_METADATA_UNAVAILABLE", attempted=False)
        details = match.get("details")
        if not isinstance(details, dict) or not isinstance(details.get("quantization_level"), str):
            raise OllamaFailure("MODEL_METADATA_UNAVAILABLE", attempted=False)
        return match["digest"], details["quantization_level"]

    def verify_model(self) -> None:
        configured_digest = self.settings.ollama_model_digest
        configured_quantization = self.settings.ollama_quantization
        if not configured_digest or not configured_quantization:
            raise OllamaFailure("MODEL_NOT_PINNED", attempted=False)
        actual_digest, actual_quantization = self.model_metadata()
        if actual_digest != configured_digest or actual_quantization != configured_quantization:
            raise OllamaFailure("MODEL_VERSION_MISMATCH", attempted=False)

    def generate(self, prompt: str) -> tuple[str, int | None, int | None, int]:
        self.verify_model()
        start = time.monotonic()
        result = self._json(
            "/api/chat",
            {
                "model": self.settings.ollama_model,
                "stream": False,
                "format": OLLAMA_CHAT_PARAMETERS["format"],
                "think": OLLAMA_CHAT_PARAMETERS["think"],
                "options": {
                    "num_predict": self.settings.ai_report_max_output_tokens,
                    "temperature": OLLAMA_CHAT_PARAMETERS["temperature"],
                },
                "messages": [
                    {
                        "role": "system",
                        "content": SYSTEM_PROMPT,
                    },
                    {"role": "user", "content": prompt},
                ],
            },
        )
        message = result.get("message")
        if not isinstance(message, dict) or not isinstance(message.get("content"), str):
            raise OllamaFailure("INVALID_OUTPUT")
        input_tokens = result.get("prompt_eval_count")
        output_tokens = result.get("eval_count")
        if type(input_tokens) is not int or input_tokens < 0:
            input_tokens = None
        if type(output_tokens) is not int or output_tokens < 0:
            output_tokens = None
        return (
            message["content"],
            input_tokens,
            output_tokens,
            round((time.monotonic() - start) * 1000),
        )
