"""Hash-pinned, dependency-free logistic baseline."""

import hashlib
import json
import math
from dataclasses import dataclass
from functools import lru_cache
from importlib.resources import files

from finguardops_ai.ml.features import FEATURE_NAMES, FEATURE_VERSION
from finguardops_ai.ml.features_v2 import FEATURE_NAMES as FEATURE_NAMES_V2
from finguardops_ai.ml.features_v2 import FEATURE_VERSION as FEATURE_VERSION_V2
from finguardops_ai.ml.features_v3 import FEATURE_NAMES as FEATURE_NAMES_V3
from finguardops_ai.ml.features_v3 import FEATURE_VERSION as FEATURE_VERSION_V3
from finguardops_ai.ml.features_v4 import FEATURE_NAMES as FEATURE_NAMES_V4
from finguardops_ai.ml.features_v4 import FEATURE_VERSION as FEATURE_VERSION_V4


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


@lru_cache(maxsize=5)
def load_model(version: str = "fraud-logistic-v2") -> FraudModel:
    try:
        if version not in (
            "fraud-logistic-v1",
            "fraud-logistic-v2",
            "fraud-logistic-v3",
            "fraud-logistic-v4",
            "fraud-logistic-v5",
        ):
            raise ModelUnavailable("MODEL_VERSION_MISMATCH")
        suffix = version.rsplit("-", 1)[-1]
        feature_version = (
            FEATURE_VERSION_V4
            if suffix == "v5"
            else FEATURE_VERSION_V3
            if suffix == "v4"
            else FEATURE_VERSION_V2
            if suffix == "v3"
            else FEATURE_VERSION
        )
        feature_names = (
            FEATURE_NAMES_V4
            if suffix == "v5"
            else FEATURE_NAMES_V3
            if suffix == "v4"
            else FEATURE_NAMES_V2
            if suffix == "v3"
            else FEATURE_NAMES
        )
        asset = files("finguardops_ai.ml").joinpath(f"fraud_model_{suffix}.json")
        manifest_asset = files("finguardops_ai.ml").joinpath(f"fraud_model_{suffix}.manifest.json")
        raw = asset.read_bytes()
        manifest = json.loads(manifest_asset.read_text(encoding="utf-8"))
        digest = hashlib.sha256(raw).hexdigest()
        if digest != manifest["sha256"]:
            raise ModelUnavailable("MODEL_HASH_MISMATCH")
        model = json.loads(raw)
        if (
            model["featureVersion"] != feature_version
            or manifest["featureVersion"] != feature_version
            or tuple(model["featureNames"]) != feature_names
            or model["modelVersion"] != manifest["modelVersion"]
            or model["modelVersion"] != version
        ):
            raise ModelUnavailable("MODEL_VERSION_MISMATCH")
        weights = tuple(float(value) for value in model["weights"])
        bias = float(model["bias"])
        if len(weights) != len(feature_names) or not all(
            math.isfinite(value) for value in (*weights, bias)
        ):
            raise ModelUnavailable("MODEL_INVALID")
        return FraudModel(model["modelVersion"], digest, weights, bias)
    except ModelUnavailable:
        raise
    except (OSError, ValueError, KeyError, TypeError) as exc:
        raise ModelUnavailable("MODEL_UNAVAILABLE") from exc
