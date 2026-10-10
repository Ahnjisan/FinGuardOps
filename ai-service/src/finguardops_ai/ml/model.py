"""Hash-pinned, dependency-free logistic baseline."""

import hashlib
import json
import math
from dataclasses import dataclass
from functools import lru_cache
from importlib.resources import files

from finguardops_ai.ml.features import FEATURE_NAMES, FEATURE_VERSION


class ModelUnavailable(Exception):
    def __init__(self, code: str) -> None:
        super().__init__(code)
        self.code = code


@dataclass(frozen=True)
class FraudModel:
    version: str
    sha256: str
    weights: tuple[float, ...]
    bias: float

    def probability_basis_points(self, features: tuple[float, ...]) -> int:
        if len(features) != len(self.weights) or not all(math.isfinite(x) for x in features):
            raise ValueError("INVALID_FEATURES")
        logit = self.bias + sum(w * x for w, x in zip(self.weights, features, strict=True))
        probability = 1.0 / (1.0 + math.exp(-max(-40.0, min(40.0, logit))))
        return max(0, min(10000, math.floor(probability * 10000 + 0.5)))


@lru_cache(maxsize=2)
def load_model(version: str = "fraud-logistic-v2") -> FraudModel:
    try:
        if version not in ("fraud-logistic-v1", "fraud-logistic-v2"):
            raise ModelUnavailable("MODEL_VERSION_MISMATCH")
        suffix = "v1" if version == "fraud-logistic-v1" else "v2"
        asset = files("finguardops_ai.ml").joinpath(f"fraud_model_{suffix}.json")
        manifest_asset = files("finguardops_ai.ml").joinpath(f"fraud_model_{suffix}.manifest.json")
        raw = asset.read_bytes()
        manifest = json.loads(manifest_asset.read_text(encoding="utf-8"))
        digest = hashlib.sha256(raw).hexdigest()
        if digest != manifest["sha256"]:
            raise ModelUnavailable("MODEL_HASH_MISMATCH")
        model = json.loads(raw)
        if (
            model["featureVersion"] != FEATURE_VERSION
            or manifest["featureVersion"] != FEATURE_VERSION
            or tuple(model["featureNames"]) != FEATURE_NAMES
            or model["modelVersion"] != manifest["modelVersion"]
            or model["modelVersion"] != version
        ):
            raise ModelUnavailable("MODEL_VERSION_MISMATCH")
        weights = tuple(float(value) for value in model["weights"])
        bias = float(model["bias"])
        if len(weights) != len(FEATURE_NAMES) or not all(
            math.isfinite(value) for value in (*weights, bias)
        ):
            raise ModelUnavailable("MODEL_INVALID")
        return FraudModel(model["modelVersion"], digest, weights, bias)
    except ModelUnavailable:
        raise
    except (OSError, ValueError, KeyError, TypeError) as exc:
        raise ModelUnavailable("MODEL_UNAVAILABLE") from exc
