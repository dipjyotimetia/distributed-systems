"""Postgres access for document metadata and embedding chunks."""

from __future__ import annotations

import json
import os
import uuid
from collections.abc import Iterator
from contextlib import contextmanager
from datetime import UTC, datetime
from typing import Any

import psycopg
from psycopg.rows import dict_row

DATABASE_URL = os.environ.get(
    "DATABASE_URL",
    "postgres://knowledge_service_user:knowledge_service_pass@localhost:5432/banking_app?sslmode=disable",
)

# The database bootstrap provisions the schema. The service role creates its tables.
SCHEMA_SQL = """
SET search_path TO knowledge_service;

CREATE TABLE IF NOT EXISTS documents (
  id UUID PRIMARY KEY,
  title TEXT NOT NULL,
  doc_type TEXT NOT NULL,
  visibility TEXT NOT NULL,
  content_type TEXT NOT NULL,
  size_bytes BIGINT NOT NULL,
  storage_key TEXT NOT NULL,
  owner_user_id BIGINT,
  case_id TEXT,
  account_id TEXT,
  text_content TEXT NOT NULL DEFAULT '',
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS chunks (
  id UUID PRIMARY KEY,
  document_id UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
  chunk_index INT NOT NULL,
  text TEXT NOT NULL,
  embedding JSONB NOT NULL,
  UNIQUE (document_id, chunk_index)
);

CREATE INDEX IF NOT EXISTS documents_owner_idx ON documents(owner_user_id);
CREATE INDEX IF NOT EXISTS documents_type_idx ON documents(doc_type);
CREATE INDEX IF NOT EXISTS documents_case_idx ON documents(case_id);
"""


def connect() -> psycopg.Connection:
    return psycopg.connect(DATABASE_URL, row_factory=dict_row)


@contextmanager
def cursor() -> Iterator[psycopg.Cursor]:
    with connect() as conn, conn.cursor() as cur:
        cur.execute("SET search_path TO knowledge_service")
        yield cur
        conn.commit()


def migrate() -> None:
    with connect() as conn:
        with conn.cursor() as cur:
            cur.execute(SCHEMA_SQL)
        conn.commit()


def count_documents() -> int:
    with cursor() as cur:
        cur.execute("SELECT COUNT(*) AS c FROM documents")
        row = cur.fetchone()
        return int(row["c"] if row else 0)


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
    chunks: list[tuple[str, list[float]]],
) -> dict[str, Any]:
    doc_id = uuid.uuid4()
    now = datetime.now(UTC)
    with cursor() as cur:
        cur.execute(
            """
            INSERT INTO documents (
              id, title, doc_type, visibility, content_type, size_bytes,
              storage_key, owner_user_id, case_id, account_id, text_content, created_at
            ) VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)
            RETURNING *
            """,
            (
                doc_id,
                title,
                doc_type,
                visibility,
                content_type,
                size_bytes,
                storage_key,
                owner_user_id,
                case_id,
                account_id,
                text_content,
                now,
            ),
        )
        row = cur.fetchone()
        for idx, (text, embedding) in enumerate(chunks):
            cur.execute(
                """
                INSERT INTO chunks (id, document_id, chunk_index, text, embedding)
                VALUES (%s,%s,%s,%s,%s::jsonb)
                """,
                (uuid.uuid4(), doc_id, idx, text, json.dumps(embedding)),
            )
    assert row is not None
    return row


def get_document(document_id: uuid.UUID) -> dict[str, Any] | None:
    with cursor() as cur:
        cur.execute("SELECT * FROM documents WHERE id = %s", (document_id,))
        return cur.fetchone()


def list_documents(
    *,
    user_id: int | None,
    internal: bool,
    doc_type: str | None,
    case_id: str | None,
    account_id: str | None,
    limit: int,
) -> list[dict[str, Any]]:
    clauses: list[str] = []
    params: list[Any] = []
    if internal:
        pass
    elif user_id is not None:
        clauses.append("(owner_user_id = %s OR visibility IN ('BANK', 'PUBLIC_BANK'))")
        params.append(user_id)
    else:
        clauses.append("visibility = 'PUBLIC_BANK'")
    if doc_type:
        clauses.append("doc_type = %s")
        params.append(doc_type)
    if case_id:
        clauses.append("case_id = %s")
        params.append(case_id)
    if account_id:
        clauses.append("account_id = %s")
        params.append(account_id)
    where = f"WHERE {' AND '.join(clauses)}" if clauses else ""
    params.append(limit)
    with cursor() as cur:
        cur.execute(
            f"SELECT * FROM documents {where} ORDER BY created_at DESC LIMIT %s",
            params,
        )
        return list(cur.fetchall())


def visible_chunks(
    *,
    user_id: int | None,
    internal: bool,
    doc_type: str | None,
    case_id: str | None,
) -> list[dict[str, Any]]:
    clauses: list[str] = []
    params: list[Any] = []
    if internal:
        pass
    elif user_id is not None:
        clauses.append(
            "(d.owner_user_id = %s OR d.visibility IN ('BANK', 'PUBLIC_BANK'))"
        )
        params.append(user_id)
    else:
        clauses.append("d.visibility = 'PUBLIC_BANK'")
    if doc_type:
        clauses.append("d.doc_type = %s")
        params.append(doc_type)
    if case_id:
        clauses.append("d.case_id = %s")
        params.append(case_id)
    where = f"WHERE {' AND '.join(clauses)}" if clauses else ""
    with cursor() as cur:
        cur.execute(
            f"""
            SELECT c.id AS chunk_id, c.document_id, c.chunk_index, c.text, c.embedding,
                   d.title, d.doc_type, d.visibility
            FROM chunks c
            JOIN documents d ON d.id = c.document_id
            {where}
            """,
            params,
        )
        return list(cur.fetchall())


def find_policy_by_title(title: str) -> dict[str, Any] | None:
    with cursor() as cur:
        cur.execute(
            "SELECT * FROM documents WHERE doc_type = 'POLICY' AND title = %s LIMIT 1",
            (title,),
        )
        return cur.fetchone()


def to_api_document(row: dict[str, Any]) -> dict[str, Any]:
    return {
        "id": str(row["id"]),
        "title": row["title"],
        "docType": row["doc_type"],
        "visibility": row["visibility"],
        "contentType": row["content_type"],
        "sizeBytes": int(row["size_bytes"]),
        "ownerUserId": row["owner_user_id"],
        "caseId": row["case_id"],
        "accountId": row["account_id"],
        "createdAt": row["created_at"].isoformat() if row.get("created_at") else None,
    }
