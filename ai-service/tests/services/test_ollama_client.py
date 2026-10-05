import pytest

from finguardops_ai.core.config import Settings
from finguardops_ai.services.ai_report import observed_model_version
from finguardops_ai.services.ollama_client import OllamaClient, OllamaFailure


def test_external_origin_refused() -> None:
    with pytest.raises(ValueError):
        OllamaClient(Settings(ollama_base_url="https://api.example.com"))


def test_digest_and_quantization_are_checked(monkeypatch: pytest.MonkeyPatch) -> None:
    settings = Settings(ollama_model_digest="a" * 64, ollama_quantization="Q4_K_M")
    client = OllamaClient(settings)
    monkeypatch.setattr(
        client,
        "_json",
        lambda path: {
            "models": [
                {
                    "name": "qwen3.5:4b",
                    "digest": "b" * 64,
                    "details": {"quantization_level": "Q4_K_M"},
                }
            ]
        },
    )
    with pytest.raises(OllamaFailure, match="MODEL_VERSION_MISMATCH"):
        client.verify_model()


def test_unpinned_model_never_calls_provider() -> None:
    with pytest.raises(OllamaFailure, match="MODEL_NOT_PINNED"):
        OllamaClient(Settings()).verify_model()


def test_observed_digest_changes_opaque_cache_identity(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    settings = Settings(ollama_model_digest="a" * 64, ollama_quantization="Q4_K_M")
    client = OllamaClient(settings)
    observed = {"digest": "a" * 64, "quantization_level": "Q4_K_M"}
    monkeypatch.setattr(
        client,
        "_json",
        lambda path: {
            "models": [
                {
                    "name": "qwen3.5:4b",
                    "digest": observed["digest"],
                    "details": {"quantization_level": observed["quantization_level"]},
                }
            ]
        },
    )
    original = observed_model_version(settings, client)
    observed["digest"] = "b" * 64
    assert observed_model_version(settings, client) != original
    observed["digest"] = "a" * 64
    observed["quantization_level"] = "Q8_0"
    assert observed_model_version(settings, client) != original


def test_chat_disables_thinking_and_keeps_json_output_bounded(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    client = OllamaClient(Settings(ai_report_max_output_tokens=384))
    monkeypatch.setattr(client, "verify_model", lambda: None)
    captured: dict = {}

    def reply(path: str, body: dict) -> dict:
        captured["path"] = path
        captured["body"] = body
        return {"message": {"content": "{}"}, "prompt_eval_count": 2, "eval_count": 3}

    monkeypatch.setattr(client, "_json", reply)
    client.generate("synthetic RULE evidence")
    assert captured["path"] == "/api/chat"
    assert captured["body"]["think"] is False
    assert captured["body"]["format"] == "json"
    assert captured["body"]["options"] == {"num_predict": 384, "temperature": 0}
