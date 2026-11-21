"""Claim-check helpers: keep large payloads out of Temporal Event History."""

from __future__ import annotations

import json
import os
import uuid
from pathlib import Path
from typing import Any

from temporalio import activity

CLAIM_CHECK_DIR = Path(os.environ.get("CLAIM_CHECK_DIR", "/tmp/ai-claim-check"))
CLAIM_CHECK_THRESHOLD = int(os.environ.get("CLAIM_CHECK_THRESHOLD_BYTES", "2048"))


def _ensure_dir() -> Path:
    CLAIM_CHECK_DIR.mkdir(parents=True, exist_ok=True)
    return CLAIM_CHECK_DIR


def should_offload(payload: Any) -> bool:
    encoded = json.dumps(payload, default=str)
    return len(encoded.encode("utf-8")) >= CLAIM_CHECK_THRESHOLD


@activity.defn(name="claimCheckPut")
async def claim_check_put(payload: Any) -> str:
    """Store a payload and return a claim key."""
    key = str(uuid.uuid4())
    path = _ensure_dir() / f"{key}.json"
    path.write_text(json.dumps(payload, default=str), encoding="utf-8")
    return key


@activity.defn(name="claimCheckGet")
async def claim_check_get(key: str) -> Any:
    """Retrieve a payload by claim key."""
    path = _ensure_dir() / f"{key}.json"
    if not path.exists():
        raise FileNotFoundError(f"claim check key not found: {key}")
    return json.loads(path.read_text(encoding="utf-8"))


def put_sync(payload: Any) -> str:
    """Synchronous put for unit tests and in-activity helpers."""
    key = str(uuid.uuid4())
    path = _ensure_dir() / f"{key}.json"
    path.write_text(json.dumps(payload, default=str), encoding="utf-8")
    return key


def get_sync(key: str) -> Any:
    path = _ensure_dir() / f"{key}.json"
    if not path.exists():
        raise FileNotFoundError(f"claim check key not found: {key}")
    return json.loads(path.read_text(encoding="utf-8"))
