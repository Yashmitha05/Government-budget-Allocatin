"""Persistent storage of the model registry in Cloudflare R2 (S3-compatible).

Cloud Run's filesystem is temporary, so in production the registry folder (models/<target>/v<N>/...,
models/<target>/active.json) is mirrored to an R2 bucket:
  * at startup:  if the bucket prefix is empty, the image's seed models are uploaded (first deploy);
                 otherwise every object is downloaded, so versions created earlier are restored;
  * after a retrain or calibration: the target's folder is uploaded, so the new version survives restarts.
Without R2 settings (local development) no store is created and the local folder is used as before.

Environment: R2_ACCOUNT_ID (or R2_ENDPOINT_URL), R2_ACCESS_KEY_ID, R2_SECRET_ACCESS_KEY, R2_BUCKET,
             R2_PREFIX (default "models/").
"""
from __future__ import annotations

import logging
import os
from pathlib import Path

log = logging.getLogger("ml-service.artifacts")


class ArtifactStore:
    def __init__(self, client, bucket: str, prefix: str, root: Path):
        self.client = client          # boto3 S3 client (or a test double with the same four methods)
        self.bucket = bucket
        self.prefix = prefix.strip("/") + "/" if prefix.strip("/") else ""
        self.root = Path(root)

    @classmethod
    def from_env(cls, root: Path) -> "ArtifactStore | None":
        bucket = os.environ.get("R2_BUCKET")
        if not bucket:
            return None
        endpoint = os.environ.get("R2_ENDPOINT_URL") or f"https://{os.environ['R2_ACCOUNT_ID']}.r2.cloudflarestorage.com"
        import boto3   # imported lazily: only needed when R2 is configured
        client = boto3.client("s3", endpoint_url=endpoint, region_name="auto",
                              aws_access_key_id=os.environ["R2_ACCESS_KEY_ID"],
                              aws_secret_access_key=os.environ["R2_SECRET_ACCESS_KEY"])
        return cls(client, bucket, os.environ.get("R2_PREFIX", "models/"), root)

    def _remote_keys(self) -> list[str]:
        keys, token = [], None
        while True:
            kwargs = {"Bucket": self.bucket, "Prefix": self.prefix}
            if token:
                kwargs["ContinuationToken"] = token
            page = self.client.list_objects_v2(**kwargs)
            keys += [o["Key"] for o in page.get("Contents", [])]
            if not page.get("IsTruncated"):
                return keys
            token = page.get("NextContinuationToken")

    def _local_files(self, sub: str = "") -> list[Path]:
        base = self.root / sub
        return sorted(p for p in base.rglob("*") if p.is_file()) if base.exists() else []

    def pull(self) -> int:
        """Download every stored object into the local registry folder (remote is the source of truth)."""
        keys = self._remote_keys()
        for key in keys:
            rel = key[len(self.prefix):]
            if not rel or rel.endswith("/"):
                continue
            dest = self.root / rel
            dest.parent.mkdir(parents=True, exist_ok=True)
            self.client.download_file(self.bucket, key, str(dest))
        return len(keys)

    def push(self, sub: str = "") -> int:
        """Upload the local registry folder (or one target's sub-folder) to the bucket."""
        files = self._local_files(sub)
        for path in files:
            key = self.prefix + path.relative_to(self.root).as_posix()
            self.client.upload_file(str(path), self.bucket, key)
        return len(files)

    def sync_on_startup(self) -> str:
        if self._remote_keys():
            n = self.pull()
            log.info("restored %d model files from r2://%s/%s", n, self.bucket, self.prefix)
            return f"pulled {n}"
        n = self.push()
        log.info("bucket empty: uploaded %d seed model files to r2://%s/%s", n, self.bucket, self.prefix)
        return f"seeded {n}"
