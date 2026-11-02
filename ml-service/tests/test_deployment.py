"""Production behaviour: service-key protection and model persistence through an S3-compatible store (R2).

The store is exercised with an in-memory stand-in for the S3 client, so no network or credentials are needed.
"""
import shutil
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from budget_ml.artifact_store import ArtifactStore
from budget_ml.config import MODEL_DIR, PROCESSED_DIR


class FakeS3:
    """Implements the four boto3 S3 client calls the store uses, backed by a dict; pages of 3 keys."""

    def __init__(self):
        self.objects: dict[tuple[str, str], bytes] = {}

    def list_objects_v2(self, Bucket, Prefix, ContinuationToken=None):
        keys = sorted(k for b, k in self.objects if b == Bucket and k.startswith(Prefix))
        start = int(ContinuationToken or 0)
        page = keys[start:start + 3]
        more = start + 3 < len(keys)
        out = {"Contents": [{"Key": k} for k in page], "IsTruncated": more}
        if more:
            out["NextContinuationToken"] = str(start + 3)
        return out

    def upload_file(self, filename, bucket, key):
        self.objects[(bucket, key)] = Path(filename).read_bytes()

    def download_file(self, bucket, key, filename):
        Path(filename).write_bytes(self.objects[(bucket, key)])


def _seed_dir(tmp_path, name="models"):
    if not any(MODEL_DIR.glob("*/active.json")):
        pytest.skip("trained models not present")
    d = tmp_path / name
    shutil.copytree(MODEL_DIR, d)
    return d


def test_store_seeds_empty_bucket_then_restores_into_a_fresh_container(tmp_path):
    s3 = FakeS3()
    first = ArtifactStore(s3, "bkt", "models/", _seed_dir(tmp_path, "a"))
    assert first.sync_on_startup().startswith("seeded")
    n_seed = len(s3.objects)
    assert n_seed > 3                              # more than one page, so pagination is exercised

    fresh = tmp_path / "empty"
    fresh.mkdir()
    second = ArtifactStore(s3, "bkt", "models/", fresh)
    assert second.sync_on_startup() == f"pulled {n_seed}"
    assert (fresh / "revenue_expenditure" / "active.json").read_bytes() == \
        (MODEL_DIR / "revenue_expenditure" / "active.json").read_bytes()


def test_push_of_one_target_uploads_only_that_target(tmp_path):
    s3 = FakeS3()
    store = ArtifactStore(s3, "bkt", "models", _seed_dir(tmp_path))
    store.push("own_tax_revenue")
    assert s3.objects and all(k.startswith("models/own_tax_revenue/") for _, k in s3.objects)


def test_store_disabled_without_configuration(monkeypatch, tmp_path):
    monkeypatch.delenv("R2_BUCKET", raising=False)
    assert ArtifactStore.from_env(tmp_path) is None


@pytest.fixture()
def processed():
    if not (PROCESSED_DIR / "panel.csv").exists():
        pytest.skip("processed dataset not present")
    return PROCESSED_DIR


def test_service_key_required_except_health(processed, tmp_path):
    from app.main import create_app
    app = create_app(processed, _seed_dir(tmp_path), api_key="s3cret", artifact_store=None)
    with TestClient(app) as c:
        assert c.get("/health").status_code == 200
        r = c.get("/models")
        assert r.status_code == 401 and r.json()["error"] == "UNAUTHORIZED"
        assert c.get("/models", headers={"X-ML-Api-Key": "wrong"}).status_code == 401
        assert c.get("/docs").status_code == 401           # interactive docs are not public either
        assert c.get("/models", headers={"X-ML-Api-Key": "s3cret"}).status_code == 200


def test_retrained_version_survives_a_container_restart(processed, tmp_path):
    from app.main import create_app
    s3 = FakeS3()
    models_a = _seed_dir(tmp_path, "a")
    app = create_app(processed, models_a, api_key=None, artifact_store=ArtifactStore(s3, "bkt", "models/", models_a))
    with TestClient(app) as c:
        before = max(m["version"] for m in c.get("/models", params={"target": "social_sector_expenditure"}).json())
        r = c.post("/retrain", json={"target": "social_sector_expenditure"})
        assert r.status_code == 200, r.text
        new_version = r.json()["version"]
    assert new_version == before + 1
    assert ("bkt", f"models/social_sector_expenditure/v{new_version}/model.joblib") in s3.objects

    # a new container starts from the image's seed models only; the new version must come back from the store
    models_b = _seed_dir(tmp_path, "b")
    app2 = create_app(processed, models_b, api_key=None, artifact_store=ArtifactStore(s3, "bkt", "models/", models_b))
    with TestClient(app2) as c:
        versions = [m["version"] for m in c.get("/models", params={"target": "social_sector_expenditure"}).json()]
    assert new_version in versions
