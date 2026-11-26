"""Seed bank policy corpus for fixture RAG demos."""

from __future__ import annotations

import logging
from pathlib import Path

import storage
import store

logger = logging.getLogger(__name__)

SEED_DIR = Path(__file__).resolve().parent / "seed_data" / "policies"


def seed_policies() -> int:
    """Insert missing policy docs and re-hydrate blobs after ephemeral storage loss."""
    if not SEED_DIR.exists():
        return 0
    created = 0
    restored = 0
    for path in sorted(SEED_DIR.glob("*.md")):
        title = path.stem.replace("-", " ").title()
        body = path.read_text(encoding="utf-8")
        payload = body.encode("utf-8")
        key = f"policy/{path.name}"
        existing = store.find_policy_by_title(title)
        if existing:
            storage_key = existing.get("storage_key") or key
            if not storage.exists(storage_key):
                storage.put_bytes(storage_key, payload, "text/markdown")
                restored += 1
                logger.info("Restored missing policy blob %s", title)
            continue
        storage.put_bytes(key, payload, "text/markdown")
        try:
            store.insert_document(
                title=title,
                doc_type="POLICY",
                visibility="PUBLIC_BANK",
                content_type="text/markdown",
                size_bytes=len(payload),
                storage_key=key,
                text_content=body,
                owner_user_id=None,
                case_id=None,
                account_id=None,
            )
        except Exception:
            storage.delete_bytes(key)
            raise
        created += 1
        logger.info("Seeded policy document %s", title)
    if restored:
        logger.info("Restored %s policy blobs after storage loss", restored)
    return created
