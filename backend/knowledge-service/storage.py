"""Blob storage: local filesystem (tests/default) or S3-compatible (RustFS/MinIO)."""

from __future__ import annotations

import os
from pathlib import Path


def backend_name() -> str:
    return os.environ.get("STORAGE_BACKEND", "local").lower()


def _local_root() -> Path:
    return Path(os.environ.get("LOCAL_BLOB_ROOT", "/tmp/knowledge-blobs")).resolve()


def _bucket() -> str:
    return os.environ.get("AWS_S3_BUCKET", "knowledge")


def _safe_local_path(key: str) -> Path:
    """Resolve key under the blob root; reject path traversal."""
    root = _local_root()
    raw = Path(key)
    if raw.is_absolute() or ".." in raw.parts or not key or key.strip() != key:
        raise ValueError("invalid storage key")
    parts = [part for part in raw.parts if part not in ("", ".")]
    if not parts:
        raise ValueError("empty storage key")
    path = root.joinpath(*parts).resolve()
    if not path.is_relative_to(root):
        raise ValueError("storage key escapes blob root")
    return path


def _s3_client():
    import boto3
    from botocore.client import Config

    endpoint = os.environ.get("AWS_S3_ENDPOINT_URL", "").rstrip("/")
    kwargs: dict = {
        "service_name": "s3",
        "region_name": os.environ.get("AWS_REGION", "us-east-1"),
        "aws_access_key_id": os.environ.get("AWS_ACCESS_KEY_ID", "rustfsadmin"),
        "aws_secret_access_key": os.environ.get("AWS_SECRET_ACCESS_KEY", "rustfsadmin"),
    }
    if endpoint:
        kwargs["endpoint_url"] = endpoint
        kwargs["config"] = Config(s3={"addressing_style": "path"})
    return boto3.client(**kwargs)


def ensure_ready() -> None:
    if backend_name() == "s3":
        client = _s3_client()
        bucket = _bucket()
        try:
            client.head_bucket(Bucket=bucket)
        except Exception:
            client.create_bucket(Bucket=bucket)
        return
    _local_root().mkdir(parents=True, exist_ok=True)


def put_bytes(key: str, data: bytes, content_type: str) -> None:
    if backend_name() == "s3":
        _s3_client().put_object(
            Bucket=_bucket(), Key=key, Body=data, ContentType=content_type
        )
        return
    path = _safe_local_path(key)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)


def get_bytes(key: str) -> bytes:
    if backend_name() == "s3":
        obj = _s3_client().get_object(Bucket=_bucket(), Key=key)
        return obj["Body"].read()
    path = _safe_local_path(key)
    if not path.exists():
        raise FileNotFoundError(key)
    return path.read_bytes()


def exists(key: str) -> bool:
    if backend_name() == "s3":
        try:
            _s3_client().head_object(Bucket=_bucket(), Key=key)
            return True
        except Exception:
            return False
    try:
        return _safe_local_path(key).exists()
    except ValueError:
        return False


def delete_bytes(key: str) -> None:
    if backend_name() == "s3":
        try:
            _s3_client().delete_object(Bucket=_bucket(), Key=key)
        except Exception:
            return
        return
    try:
        path = _safe_local_path(key)
    except ValueError:
        return
    if path.exists():
        path.unlink()
