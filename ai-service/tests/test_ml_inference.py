import json
from dataclasses import replace
from datetime import UTC, datetime, timedelta
from decimal import Decimal
from pathlib import Path
from tempfile import TemporaryDirectory

import pytest
from fastapi.testclient import TestClient

from finguardops_ai.main import create_app
from finguardops_ai.ml.features import extract_features
from finguardops_ai.ml.model import ModelUnavailable, load_model
from finguardops_ai.ml.train import build_artifacts, generate, verify_split

CUTOFF = datetime(2026, 10, 10, 0, 0, tzinfo=UTC)
TX = "00000000-0000-4000-8000-000000000380"
EVENT = "00000000-0000-4000-8000-000000000381"


def request_body() -> dict[str, object]:
    model = load_model()
    return {
        "transactionId": TX,
        "evaluationCutoffAt": CUTOFF.isoformat().replace("+00:00", "Z"),
        "amount": "5000000.0000",
        "transactionType": "ACCOUNT_TRANSFER",
        "channel": "MOBILE_BANKING",
        "featureVersion": "fraud-feature-v1",
        "scoringPolicyVersion": "rule-ml-policy-v1",
        "modelVersion": model.version,
        "modelSha256": model.sha256,
        "events": [
            {
                "eventId": EVENT,
                "eventType": "DEVICE_REGISTERED",
                "occurredAt": (CUTOFF - timedelta(hours=1)).isoformat().replace("+00:00", "Z"),
                "createdAt": (CUTOFF - timedelta(minutes=59)).isoformat().replace("+00:00", "Z"),
            }
        ],
    }


def test_synthetic_split_has_no_customer_transaction_or_time_overlap() -> None:
    splits = generate()
    verify_split(splits)
    bad = {name: list(rows) for name, rows in splits.items()}
    bad["test"][0] = bad["train"][0]
    with pytest.raises(ValueError, match="GROUP_LEAKAGE"):
        verify_split(bad)
    bad = {name: list(rows) for name, rows in splits.items()}
    bad["test"][0] = replace(
        bad["test"][0], occurred_at=max(row.occurred_at for row in bad["validation"])
    )
    with pytest.raises(ValueError, match="TIME_LEAKAGE"):
        verify_split(bad)


def test_synthetic_transactions_use_supported_channel_pairs() -> None:
    # This generator uses ACCOUNT_TRANSFER and ATM_WITHDRAWAL only.
    # The backend permits MOBILE_BANKING for the former and ATM for the latter.
    for rows in generate().values():
        for row in rows:
            assert row.features[1] == row.features[2]


def test_versioned_artifacts_and_metrics_reproduce() -> None:
    original = Path(__file__).resolve().parents[1] / "src" / "finguardops_ai" / "ml"
    with TemporaryDirectory() as directory:
        generated = Path(directory)
        for version in ("v1", "v2"):
            report = build_artifacts(generated, version)
            assert report["dataVersion"] == "synthetic-fraud-" + version
            assert report["modelVersion"] == "fraud-logistic-" + version
            assert report["modelSha256"] == load_model("fraud-logistic-" + version).sha256
            for suffix in ("json", "manifest.json"):
                filename = f"fraud_model_{version}.{suffix}"
                assert (generated / filename).read_bytes() == (original / filename).read_bytes()
            recorded = json.loads((original / f"fraud_model_{version}.evaluation.json").read_text())
            assert report["test"] == recorded["test"]
            assert report["validation"] == recorded["validation"]


def test_model_hash_and_deterministic_inference() -> None:
    model = load_model()
    assert len(model.sha256) == 64
    vector = extract_features(
        amount=Decimal("5000000"),
        transaction_type="ACCOUNT_TRANSFER",
        channel="MOBILE_BANKING",
        cutoff=CUTOFF,
        events=(),
    )
    assert model.probability_basis_points(vector) == model.probability_basis_points(vector)


def test_historical_model_version_remains_addressable() -> None:
    old = load_model("fraud-logistic-v1")
    assert old.sha256 == "93a91797f6a652a08871191e87385ced6edd1a560abd6ee9b2782411bb19c286"
    body = request_body()
    body["modelVersion"] = old.version
    body["modelSha256"] = old.sha256
    response = TestClient(create_app()).post("/api/v1/ml-inference", json=body)
    assert response.status_code == 200
    assert response.json()["modelVersion"] == old.version


def test_endpoint_and_invalid_model_version() -> None:
    client = TestClient(create_app())
    body = request_body()
    response = client.post("/api/v1/ml-inference", json=body)
    assert response.status_code == 200
    result = response.json()
    assert result["transactionId"] == TX
    assert result["modelSha256"] == body["modelSha256"]
    assert result["scoringPolicyVersion"] == "rule-ml-policy-v1"
    assert 0 <= result["probabilityBasisPoints"] <= 10000
    body["modelVersion"] = "wrong-model"
    assert client.post("/api/v1/ml-inference", json=body).status_code == 503
    body = request_body()
    body["scoringPolicyVersion"] = "wrong-policy"
    assert client.post("/api/v1/ml-inference", json=body).status_code == 400


def test_future_late_and_duplicate_events_rejected() -> None:
    client = TestClient(create_app())
    body = request_body()
    events = body["events"]
    assert isinstance(events, list)
    events.append(dict(events[0]))
    assert client.post("/api/v1/ml-inference", json=body).status_code == 422
    events.pop()
    events[0]["occurredAt"] = (CUTOFF + timedelta(seconds=1)).isoformat().replace("+00:00", "Z")
    assert client.post("/api/v1/ml-inference", json=body).status_code == 422
    events[0]["occurredAt"] = (CUTOFF - timedelta(hours=1)).isoformat().replace("+00:00", "Z")
    events[0]["createdAt"] = (CUTOFF + timedelta(seconds=1)).isoformat().replace("+00:00", "Z")
    assert client.post("/api/v1/ml-inference", json=body).status_code == 422


def test_missing_feature_and_identifiers_rejected() -> None:
    client = TestClient(create_app())
    body = request_body()
    del body["amount"]
    assert client.post("/api/v1/ml-inference", json=body).status_code == 400
    body = request_body()
    body["externalCustomerRef"] = "sensitive"
    assert client.post("/api/v1/ml-inference", json=body).status_code == 400


def test_model_absent_is_explicit(monkeypatch: pytest.MonkeyPatch) -> None:
    from finguardops_ai.services import ml_inference

    def unavailable(_version: str):
        raise ModelUnavailable("MODEL_UNAVAILABLE")

    monkeypatch.setattr(ml_inference, "load_model", unavailable)
    response = TestClient(create_app()).post("/api/v1/ml-inference", json=request_body())
    assert response.status_code == 503
    assert response.json()["detail"]["code"] == "MODEL_UNAVAILABLE"


def test_tampered_model_asset_fails_hash_check(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    from finguardops_ai.ml import model as model_module

    original = Path(model_module.files("finguardops_ai.ml"))
    (tmp_path / "fraud_model_v2.json").write_bytes(
        original.joinpath("fraud_model_v2.json").read_bytes() + b" "
    )
    (tmp_path / "fraud_model_v2.manifest.json").write_text(
        json.dumps({"sha256": load_model().sha256, "modelVersion": "fraud-logistic-v2"}),
        encoding="utf-8",
    )
    monkeypatch.setattr(model_module, "files", lambda _: tmp_path)
    model_module.load_model.cache_clear()
    try:
        with pytest.raises(ModelUnavailable, match="MODEL_HASH_MISMATCH"):
            model_module.load_model()
    finally:
        model_module.load_model.cache_clear()


def test_manifest_feature_version_must_match_model_and_runtime(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    from finguardops_ai.ml import model as model_module

    original = Path(model_module.files("finguardops_ai.ml"))
    (tmp_path / "fraud_model_v2.json").write_bytes(
        original.joinpath("fraud_model_v2.json").read_bytes()
    )
    manifest = json.loads(original.joinpath("fraud_model_v2.manifest.json").read_text())
    manifest["featureVersion"] = "unexpected-feature"
    (tmp_path / "fraud_model_v2.manifest.json").write_text(json.dumps(manifest))
    monkeypatch.setattr(model_module, "files", lambda _: tmp_path)
    model_module.load_model.cache_clear()
    try:
        with pytest.raises(ModelUnavailable, match="MODEL_VERSION_MISMATCH"):
            model_module.load_model()
    finally:
        model_module.load_model.cache_clear()
