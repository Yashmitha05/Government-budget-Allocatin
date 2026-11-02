"""File-based model registry: models/<target>/v<N>/{model.joblib, metadata.json}.

Exactly one version per target is active (recorded in models/<target>/active.json).
"""
from __future__ import annotations

import json
from datetime import datetime, timezone
from pathlib import Path

import joblib

from .config import MODEL_DIR


class ModelNotFound(LookupError):
    pass


def _target_dir(target: str, root: Path) -> Path:
    return root / target


def list_versions(target: str, root: Path = MODEL_DIR) -> list[dict]:
    d = _target_dir(target, root)
    if not d.exists():
        return []
    active = active_version(target, root)
    out = []
    for vdir in sorted(d.glob("v*"), key=lambda p: int(p.name[1:])):
        meta = json.loads((vdir / "metadata.json").read_text(encoding="utf-8"))
        meta["is_active"] = meta["version"] == active
        out.append(meta)
    return out


def active_version(target: str, root: Path = MODEL_DIR) -> int | None:
    f = _target_dir(target, root) / "active.json"
    return json.loads(f.read_text(encoding="utf-8"))["version"] if f.exists() else None


def save_version(target: str, artefact: object, metadata: dict, activate: bool, root: Path = MODEL_DIR) -> dict:
    d = _target_dir(target, root)
    d.mkdir(parents=True, exist_ok=True)
    existing = [int(p.name[1:]) for p in d.glob("v*") if p.name[1:].isdigit()]
    version = max(existing, default=0) + 1
    vdir = d / f"v{version}"
    vdir.mkdir()
    joblib.dump(artefact, vdir / "model.joblib")
    meta = {"target": target, "version": version,
            "trained_at": datetime.now(timezone.utc).isoformat(timespec="seconds"), **metadata,
            "artifact_path": str(vdir.relative_to(root.parent)).replace("\\", "/")}
    (vdir / "metadata.json").write_text(json.dumps(meta, indent=2, default=float), encoding="utf-8")
    if activate:
        set_active(target, version, root)
    meta["is_active"] = activate
    return meta


def set_active(target: str, version: int, root: Path = MODEL_DIR) -> None:
    if not (_target_dir(target, root) / f"v{version}").exists():
        raise ModelNotFound(f"{target} v{version}")
    (_target_dir(target, root) / "active.json").write_text(json.dumps({"version": version}), encoding="utf-8")


def load(target: str, version: int | None = None, root: Path = MODEL_DIR) -> tuple[object, dict]:
    version = version or active_version(target, root)
    if version is None:
        raise ModelNotFound(f"No trained model for {target}")
    vdir = _target_dir(target, root) / f"v{version}"
    if not vdir.exists():
        raise ModelNotFound(f"{target} v{version}")
    meta = json.loads((vdir / "metadata.json").read_text(encoding="utf-8"))
    meta["is_active"] = version == active_version(target, root)
    return joblib.load(vdir / "model.joblib"), meta
