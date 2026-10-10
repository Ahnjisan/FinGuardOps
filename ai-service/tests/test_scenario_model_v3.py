"""The local synthetic v3 artifact remains reproducible and separated by customer/time."""

import json
from pathlib import Path
from tempfile import TemporaryDirectory

from finguardops_ai.ml.model import load_model
from finguardops_ai.ml.train_v3 import build_artifacts, generate, verify_split


def test_v3_synthetic_split_and_artifact_hash_reproduce() -> None:
    splits = generate()
    verify_split(splits)
    assert len({row.customer_group for row in splits["train"]}
               & {row.customer_group for row in splits["test"]}) == 0
    source = Path(__file__).resolve().parents[1] / "src" / "finguardops_ai" / "ml"
    with TemporaryDirectory() as folder:
        report = build_artifacts(Path(folder))
        assert report["modelSha256"] == load_model("fraud-logistic-v3").sha256
        assert "Synthetic local evaluation only" in report["warning"]
        for suffix in ("json", "manifest.json", "evaluation.json"):
            filename = f"fraud_model_v3.{suffix}"
            assert (Path(folder) / filename).read_bytes() == (source / filename).read_bytes()
        manifest = json.loads((source / "fraud_model_v3.manifest.json").read_text())
        assert manifest["sha256"] == report["modelSha256"]
