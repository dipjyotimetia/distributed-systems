"""Document store facade: Postgres (Compose) or in-memory (tests)."""

from __future__ import annotations

import json
import os
import uuid
from datetime import UTC, datetime
from typing import Any

import db as pg
from embeddings import chunk_text, embed_text

_memory_docs: list[dict[str, Any]] = []
_memory_chunks: list[dict[str, Any]] = []


def _use_memory() -> bool:
    return os.environ.get("KNOWLEDGE_STORE", "postgres").lower() == "memory"


def reset_memory() -> None:
    _memory_docs.clear()
    _memory_chunks.clear()


def migrate() -> None:
    if _use_memory():
        return
    pg.migrate()


def count_documents() -> int:
    if _use_memory():
        return len(_memory_docs)
    return pg.count_documents()


def insert_document(
    *,
    title: str,
    doc_type: str,
    visibility: str,
    content_type: str,
    size_bytes: int,
    storage_key: str,
    text_content: str,
    owner_user_id: int | None,
    case_id: str | None,
    account_id: str | None,
) -> dict[str, Any]:
    pieces = chunk_text(text_content)
    chunks = [(piece, embed_text(piece)) for piece in pieces]
    if _use_memory():
        doc_id = uuid.uuid4()
        now = datetime.now(UTC)
        row = {
            "id": doc_id,
            "title": title,
            "doc_type": doc_type,
            "visibility": visibility,
            "content_type": content_type,
            "size_bytes": size_bytes,
            "storage_key": storage_key,
            "owner_user_id": owner_user_id,
            "case_id": case_id,
            "account_id": account_id,
            "text_content": text_content,
            "created_at": now,
        }
        _memory_docs.append(row)
        for idx, (text, embedding) in enumerate(chunks):
            _memory_chunks.append(
                {
                    "id": uuid.uuid4(),
                    "document_id": doc_id,
                    "chunk_index": idx,
                    "text": text,
                    "embedding": embedding,
                    "title": title,
                    "doc_type": doc_type,
                    "visibility": visibility,
                }
            )
        return row
    return pg.insert_document(
        title=title,
        doc_type=doc_type,
        visibility=visibility,
        content_type=content_type,
        size_bytes=size_bytes,
        storage_key=storage_key,
        text_content=text_content,
        owner_user_id=owner_user_id,
        case_id=case_id,
        account_id=account_id,
        chunks=chunks,
    )


def get_document(document_id: uuid.UUID) -> dict[str, Any] | None:
    if _use_memory():
        for row in _memory_docs:
            if row["id"] == document_id:
                return row
        return None
    return pg.get_document(document_id)


def list_documents(
    *,
    user_id: int | None,
    internal: bool,
    doc_type: str | None,
    case_id: str | None,
    account_id: str | None,
    limit: int,
) -> list[dict[str, Any]]:
    if not _use_memory():
        return pg.list_documents(
            user_id=user_id,
            internal=internal,
            doc_type=doc_type,
            case_id=case_id,
            account_id=account_id,
            limit=limit,
        )
    items: list[dict[str, Any]] = []
    for row in _memory_docs:
        if not _visible(row, user_id=user_id, internal=internal):
            continue
        if doc_type and row["doc_type"] != doc_type:
            continue
        if case_id and row["case_id"] != case_id:
            continue
        if account_id and row["account_id"] != account_id:
            continue
        items.append(row)
    items.sort(key=lambda r: r["created_at"], reverse=True)
    return items[:limit]


def visible_chunks(
    *,
    user_id: int | None,
    internal: bool,
    doc_type: str | None,
    case_id: str | None,
) -> list[dict[str, Any]]:
    if not _use_memory():
        rows = pg.visible_chunks(
            user_id=user_id,
            internal=internal,
            doc_type=doc_type,
            case_id=case_id,
        )
        for row in rows:
            emb = row["embedding"]
            if isinstance(emb, str):
                row["embedding"] = json.loads(emb)
            elif isinstance(emb, memoryview):
                row["embedding"] = json.loads(bytes(emb).decode())
        return rows
    out: list[dict[str, Any]] = []
    docs = {d["id"]: d for d in _memory_docs}
    for chunk in _memory_chunks:
        doc = docs.get(chunk["document_id"])
        if not doc or not _visible(doc, user_id=user_id, internal=internal):
            continue
        if doc_type and doc["doc_type"] != doc_type:
            continue
        if case_id and doc["case_id"] != case_id:
            continue
        out.append(
            {
                **chunk,
                "title": doc["title"],
                "doc_type": doc["doc_type"],
                "visibility": doc["visibility"],
            }
        )
    return out


def find_policy_by_title(title: str) -> dict[str, Any] | None:
    if _use_memory():
        for row in _memory_docs:
            if row["doc_type"] == "POLICY" and row["title"] == title:
                return row
        return None
    return pg.find_policy_by_title(title)


def to_api_document(row: dict[str, Any]) -> dict[str, Any]:
    return pg.to_api_document(row)


def _visible(row: dict[str, Any], *, user_id: int | None, internal: bool) -> bool:
    if internal:
        return True
    if row["visibility"] in ("BANK", "PUBLIC_BANK"):
        return True
    return user_id is not None and row["owner_user_id"] == user_id
