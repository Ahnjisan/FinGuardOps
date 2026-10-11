"""Reproducible customer/time-separated synthetic evaluation for v3 only."""

import hashlib
import json
import math
import random
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from decimal import Decimal
from pathlib import Path
from uuid import UUID

from finguardops_ai.ml.features_v2 import FEATURE_NAMES, FEATURE_VERSION, extract_features
from finguardops_ai.ml.model import FraudModel
from finguardops_ai.ml.train import metrics, verify_split
from finguardops_ai.schemas.scenario_rule import ScenarioFactsDto

SEED = 384


@dataclass(frozen=True)
class Sample:
    customer_group: int
    transaction_id: UUID
    occurred_at: datetime
    features: tuple[float, ...]
    label: int


def _uuid(number: int) -> UUID:
    return UUID(int=number + 10**12, version=4)


def _facts(rng: random.Random, cutoff: datetime, amount: Decimal, number: int) -> ScenarioFactsDto:
    baseline = rng.random() < 0.82
    base = Decimal(rng.randrange(50_000, 2_000_000))
    new_device = rng.random() < 0.24
    first = rng.random() < 0.31
    sequence = rng.random() < 0.19
    material = sequence and rng.random() < 0.45
    app = rng.random() < 0.11
    overseas = rng.random() < 0.08
    password_at = cutoff - timedelta(minutes=20) if sequence else None
    limit_at = cutoff - timedelta(minutes=10) if sequence else None
    previous = Decimal("2000000") if sequence else None
    next_limit = Decimal("7000000" if material else "3000000") if sequence else None
    return ScenarioFactsDto.model_validate(
        {
            "sourceVersion": "SCN001-002-contract-v1",
            "baselineStatus": "AVAILABLE" if baseline else "INSUFFICIENT",
            "baselineCount": 10 if baseline else 2,
            "baselineDays": 3 if baseline else 1,
            "baselineTotal": base * (10 if baseline else 2),
            "baselineMean": base if baseline else None,
            "recipientStatus": "FIRST" if first else "KNOWN",
            "beneficiaryEventId": str(_uuid(number * 10 + 1)) if first else None,
            "beneficiaryRegisteredAt": (cutoff - timedelta(minutes=8))
            .isoformat()
            .replace("+00:00", "Z")
            if first
            else None,
            "deviceStatus": "NEW_UNVERIFIED" if new_device else "KNOWN",
            "deviceEventId": str(_uuid(number * 10 + 2)) if new_device else None,
            "deviceFirstSeenAt": (cutoff - timedelta(minutes=7)).isoformat().replace("+00:00", "Z")
            if new_device
            else None,
            "limitStatus": "RAISED" if sequence else "UNKNOWN",
            "limitScope": "PER_TRANSFER" if sequence else None,
            "previousLimit": previous,
            "newLimit": next_limit,
            "priorLimitExceeded": "FALSE" if sequence else "UNKNOWN",
            "passwordEventId": str(_uuid(number * 10 + 3)) if sequence else None,
            "passwordChangedAt": password_at.isoformat().replace("+00:00", "Z")
            if sequence
            else None,
            "limitEventId": str(_uuid(number * 10 + 4)) if sequence else None,
            "limitChangedAt": limit_at.isoformat().replace("+00:00", "Z") if sequence else None,
            "identityStatus": "FAILED" if rng.random() < 0.07 else "VERIFIED",
            "appSecurityStatus": "ALERT" if app else "CLEAR",
            "appSecurityAt": (cutoff - timedelta(minutes=6)).isoformat().replace("+00:00", "Z")
            if app
            else None,
            "networkRegionStatus": "OVERSEAS" if overseas else "DOMESTIC",
            "networkRegionAt": (cutoff - timedelta(minutes=4)).isoformat().replace("+00:00", "Z")
            if overseas
            else None,
            "exactProviderDeviceMatch": rng.random() < 0.07,
            "transferCoverageFactId": str(_uuid(number * 10 + 5)) if baseline or first else None,
            "deviceCoverageFactId": str(_uuid(number * 10 + 6)) if new_device else None,
            "deviceTrustFactId": str(_uuid(number * 10 + 7)) if new_device else None,
            "limitFactId": str(_uuid(number * 10 + 8)) if sequence else None,
            "identityFactId": None,
            "appSecurityFactId": str(_uuid(number * 10 + 9)) if app else None,
            "networkRegionFactId": str(_uuid(number * 10 + 10)) if overseas else None,
        }
    )


def generate() -> dict[str, list[Sample]]:
    rng = random.Random(SEED)
    splits: dict[str, list[Sample]] = {"train": [], "validation": [], "test": []}
    for phase, split in enumerate(splits):
        base = datetime(2025, 1, 1, tzinfo=UTC) + timedelta(days=phase * 60)
        for customer in range(300):
            group = phase * 300 + customer
            latent = rng.gauss(0, 0.5)
            for transaction in range(2):
                number = group * 10 + transaction
                cutoff = base + timedelta(days=customer // 10, hours=transaction * 2)
                amount = Decimal(rng.randrange(100_000, 20_000_001))
                facts = _facts(rng, cutoff, amount, number)
                features = extract_features(
                    amount=amount, channel="MOBILE_BANKING", cutoff=cutoff, facts=facts
                )
                hidden = (
                    -4.2
                    + 1.3 * features[0]
                    + 2.5 * features[2]
                    + 0.9 * features[4]
                    + 0.6 * features[5]
                    + 1.3 * features[6]
                    + 0.5 * features[7]
                    + 0.8 * features[8]
                    + 0.7 * features[9]
                    + 1.4 * features[10]
                    + 0.5 * features[11]
                    + latent
                )
                chance = 1 / (1 + math.exp(-hidden))
                label = int(rng.random() < chance)
                splits[split].append(Sample(group, _uuid(number), cutoff, features, label))
    verify_split(splits)
    return splits


def train(rows: list[Sample]) -> tuple[tuple[float, ...], float]:
    weights = [0.0] * len(FEATURE_NAMES)
    bias = 0.0
    for _ in range(900):
        gradients = [0.0] * len(weights)
        bias_gradient = 0.0
        for row in rows:
            logit = bias + sum(w * x for w, x in zip(weights, row.features, strict=True))
            prediction = 1 / (1 + math.exp(-max(-40, min(40, logit))))
            error = prediction - row.label
            bias_gradient += error
            for index, value in enumerate(row.features):
                gradients[index] += error * value
        step = 0.25 / len(rows)
        for index in range(len(weights)):
            weights[index] -= step * (gradients[index] + 0.01 * weights[index])
        bias -= step * bias_gradient
    return tuple(round(value, 10) for value in weights), round(bias, 10)


def build_artifacts(destination: Path) -> dict[str, object]:
    splits = generate()
    weights, bias = train(splits["train"])
    model_version = "fraud-logistic-v3"
    payload = {
        "dataVersion": "synthetic-fraud-v3",
        "featureVersion": FEATURE_VERSION,
        "featureNames": FEATURE_NAMES,
        "modelVersion": model_version,
        "seed": SEED,
        "weights": weights,
        "bias": bias,
    }
    raw = (
        json.dumps(payload, sort_keys=True, separators=(",", ":"), ensure_ascii=True) + "\n"
    ).encode()
    digest = hashlib.sha256(raw).hexdigest()
    model = FraudModel(model_version, digest, weights, bias)
    report: dict[str, object] = {
        "warning": "Synthetic local evaluation only; not real financial fraud performance",
        "dataVersion": "synthetic-fraud-v3",
        "featureVersion": FEATURE_VERSION,
        "modelVersion": model_version,
        "modelSha256": digest,
        "seed": SEED,
        "split": {
            name: {"rows": len(rows), "positives": sum(row.label for row in rows)}
            for name, rows in splits.items()
        },
    }
    for name in ("validation", "test"):
        rows = splits[name]
        probabilities = [model.probability_basis_points(row.features) for row in rows]
        report[name] = [metrics(rows, probabilities, threshold) for threshold in (3000, 5000, 7000)]
    destination.mkdir(parents=True, exist_ok=True)
    (destination / "fraud_model_v3.json").write_bytes(raw)
    (destination / "fraud_model_v3.manifest.json").write_bytes(
        (
            json.dumps(
                {
                    "modelVersion": model_version,
                    "featureVersion": FEATURE_VERSION,
                    "sha256": digest,
                },
                sort_keys=True,
                indent=2,
            )
            + "\n"
        ).encode("utf-8"),
    )
    (destination / "fraud_model_v3.evaluation.json").write_bytes(
        (json.dumps(report, sort_keys=True, indent=2) + "\n").encode("utf-8")
    )
    return report


if __name__ == "__main__":
    print(build_artifacts(Path(__file__).parent)["modelSha256"])
