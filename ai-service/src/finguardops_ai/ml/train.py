"""Reproducible synthetic-only training and evaluation; run as a module."""

import hashlib
import json
import math
import random
import statistics
import time
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from decimal import Decimal
from pathlib import Path
from uuid import UUID

from finguardops_ai.ml.features import EVENT_TYPES, FEATURE_NAMES, FEATURE_VERSION, extract_features
from finguardops_ai.ml.model import FraudModel

SEED = 380


@dataclass(frozen=True)
class SyntheticEvent:
    event_id: UUID
    event_type: str
    occurred_at: datetime
    created_at: datetime


@dataclass(frozen=True)
class Sample:
    customer_group: int
    transaction_id: UUID
    occurred_at: datetime
    features: tuple[float, ...]
    label: int


def generate(version: str = "v2") -> dict[str, list[Sample]]:
    if version not in ("v1", "v2"):
        raise ValueError("UNKNOWN_SYNTHETIC_DATA_VERSION")
    rng = random.Random(SEED)
    result: dict[str, list[Sample]] = {"train": [], "validation": [], "test": []}
    # Disjoint customer cohorts occupy strictly later time periods.
    for phase, split in enumerate(result):
        base = datetime(2025, 1, 1, tzinfo=UTC) + timedelta(days=phase * 40)
        for customer in range(300):
            customer_group = phase * 300 + customer
            latent = rng.gauss(0, 0.5)
            for transaction in range(2):
                cutoff = base + timedelta(days=customer // 10, hours=transaction * 2)
                amount = Decimal(str(round(math.exp(rng.uniform(5.5, 13.8)), 2)))
                tx_type = "ACCOUNT_TRANSFER" if rng.random() < 0.7 else "ATM_WITHDRAWAL"
                if version == "v1":
                    # Frozen historical generator, including invalid type/channel pairs.
                    channel = "MOBILE_BANKING" if rng.random() < 0.6 else "CORE_BANKING"
                else:
                    channel = "MOBILE_BANKING" if tx_type == "ACCOUNT_TRANSFER" else "ATM"
                events = []
                for event_type in EVENT_TYPES:
                    for item in range(rng.randrange(4)):
                        when = cutoff - timedelta(hours=rng.uniform(0.1, 23.9))
                        events.append(
                            SyntheticEvent(
                                UUID(
                                    int=customer_group * 100000
                                    + transaction * 1000
                                    + EVENT_TYPES.index(event_type) * 100
                                    + item
                                    + 1
                                ),
                                event_type,
                                when,
                                when + timedelta(minutes=1),
                            )
                        )
                vector = extract_features(
                    amount=amount,
                    transaction_type=tx_type,
                    channel=channel,
                    cutoff=cutoff,
                    events=tuple(events),
                )
                # The label is an independently sampled Bernoulli outcome from a
                # hidden process. It is not a Rule score or post-case disposition.
                hidden_logit = (
                    -6.3
                    + 2.8 * vector[0]
                    + 0.6 * vector[1]
                    + 0.4 * vector[2]
                    + 2.5 * vector[3]
                    + 1.8 * vector[4]
                    + 1.6 * vector[5]
                    + 1.2 * vector[6]
                    + latent
                )
                chance = 1 / (1 + math.exp(-hidden_logit))
                label = int(rng.random() < chance)
                tx_id = UUID(int=10**12 + customer_group * 10 + transaction)
                result[split].append(Sample(customer_group, tx_id, cutoff, vector, label))
    verify_split(result)
    return result


def verify_split(splits: dict[str, list[Sample]]) -> None:
    seen_customers: set[int] = set()
    seen_transactions: set[UUID] = set()
    previous_max: datetime | None = None
    for name in ("train", "validation", "test"):
        rows = splits[name]
        if not rows:
            raise ValueError("EMPTY_SPLIT")
        customers = {row.customer_group for row in rows}
        transactions = {row.transaction_id for row in rows}
        if (
            customers & seen_customers
            or transactions & seen_transactions
            or len(transactions) != len(rows)
        ):
            raise ValueError("GROUP_LEAKAGE")
        if previous_max is not None and min(row.occurred_at for row in rows) <= previous_max:
            raise ValueError("TIME_LEAKAGE")
        seen_customers.update(customers)
        seen_transactions.update(transactions)
        previous_max = max(row.occurred_at for row in rows)


def train(rows: list[Sample]) -> tuple[tuple[float, ...], float]:
    weights = [0.0] * len(FEATURE_NAMES)
    bias = 0.0
    for _ in range(900):
        gradients = [0.0] * len(weights)
        bias_gradient = 0.0
        for row in rows:
            logit = bias + sum(w * x for w, x in zip(weights, row.features, strict=True))
            prediction = 1.0 / (1.0 + math.exp(-max(-40, min(40, logit))))
            error = prediction - row.label
            bias_gradient += error
            for index, value in enumerate(row.features):
                gradients[index] += error * value
        step = 0.25 / len(rows)
        for index in range(len(weights)):
            weights[index] -= step * (gradients[index] + 0.01 * weights[index])
        bias -= step * bias_gradient
    return tuple(round(value, 10) for value in weights), round(bias, 10)


def metrics(rows: list[Sample], probabilities: list[int], threshold: int) -> dict[str, object]:
    tp = fp = tn = fn = 0
    for row, probability in zip(rows, probabilities, strict=True):
        predicted = probability > threshold
        if predicted and row.label:
            tp += 1
        elif predicted:
            fp += 1
        elif row.label:
            fn += 1
        else:
            tn += 1
    precision = tp / (tp + fp) if tp + fp else 0.0
    recall = tp / (tp + fn) if tp + fn else 0.0
    return {
        "thresholdBasisPoints": threshold,
        "tp": tp,
        "fp": fp,
        "tn": tn,
        "fn": fn,
        "precision": precision,
        "recall": recall,
        "f1": 2 * precision * recall / (precision + recall) if precision + recall else 0.0,
        "falsePositiveRate": fp / (fp + tn) if fp + tn else 0.0,
        "falseNegativeRate": fn / (fn + tp) if fn + tp else 0.0,
    }


def build_artifacts(destination: Path, version: str = "v2") -> dict[str, object]:
    splits = generate(version)
    data_version = "synthetic-fraud-" + version
    model_version = "fraud-logistic-" + version
    weights, bias = train(splits["train"])
    payload = {
        "dataVersion": data_version,
        "featureVersion": FEATURE_VERSION,
        "featureNames": FEATURE_NAMES,
        "modelVersion": model_version,
        "seed": SEED,
        "weights": weights,
        "bias": bias,
    }
    raw = (
        json.dumps(payload, sort_keys=True, separators=(",", ":"), ensure_ascii=True) + "\n"
    ).encode("utf-8")
    digest = hashlib.sha256(raw).hexdigest()
    model = FraudModel(model_version, digest, weights, bias)
    report: dict[str, object] = {
        "warning": "Synthetic pipeline validation only; not real financial fraud performance",
        "dataVersion": data_version,
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
    bench_rows = splits["test"][:100]
    samples = []
    for _ in range(10):
        start = time.perf_counter_ns()
        for row in bench_rows:
            model.probability_basis_points(row.features)
        samples.append((time.perf_counter_ns() - start) / len(bench_rows) / 1_000_000)
    report["localInferenceMillisecondsPerTransaction"] = {
        "median": statistics.median(samples),
        "max": max(samples),
        "samples": len(samples),
        "scope": "model calculation only; excludes HTTP and feature query",
    }
    destination.mkdir(parents=True, exist_ok=True)
    (destination / f"fraud_model_{version}.json").write_bytes(raw)
    (destination / f"fraud_model_{version}.manifest.json").write_text(
        json.dumps(
            {"modelVersion": model_version, "featureVersion": FEATURE_VERSION, "sha256": digest},
            sort_keys=True,
            indent=2,
        )
        + "\n",
        encoding="utf-8",
    )
    (destination / f"fraud_model_{version}.evaluation.json").write_text(
        json.dumps(report, sort_keys=True, indent=2) + "\n", encoding="utf-8"
    )
    return report


if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser()
    parser.add_argument("--version", choices=("v1", "v2"), default="v2")
    selected = parser.parse_args().version
    output = Path(__file__).resolve().parent
    summary = build_artifacts(output, selected)
    print(json.dumps({"modelSha256": summary["modelSha256"], "split": summary["split"]}))
