"""Retrieval ranking over fixture embeddings."""

from __future__ import annotations

from typing import Any

import store
from embeddings import cosine, embed_text


def search(
    *,
    query: str,
    user_id: int | None,
    internal: bool,
    doc_type: str | None = None,
    case_id: str | None = None,
    limit: int = 5,
) -> list[dict[str, Any]]:
    query_vec = embed_text(query)
    chunks = store.visible_chunks(
        user_id=user_id,
        internal=internal,
        doc_type=doc_type,
        case_id=case_id,
    )
    scored: list[tuple[float, dict[str, Any]]] = []
    for chunk in chunks:
        emb = chunk["embedding"]
        if not isinstance(emb, list):
            continue
        lexical = _lexical_boost(query, chunk["text"] + " " + chunk["title"])
        score = cosine(query_vec, emb) + lexical
        scored.append((score, chunk))
    scored.sort(key=lambda item: item[0], reverse=True)

    hits: list[dict[str, Any]] = []
    seen: set[str] = set()
    for score, chunk in scored:
        doc_id = str(chunk["document_id"])
        if doc_id in seen:
            continue
        seen.add(doc_id)
        excerpt = chunk["text"][:400]
        hits.append(
            {
                "documentId": doc_id,
                "title": chunk["title"],
                "docType": chunk["doc_type"],
                "score": round(float(score), 4),
                "excerpt": excerpt,
                "citations": [f"{chunk['title']}#chunk-{chunk['chunk_index']}"],
            }
        )
        if len(hits) >= limit:
            break
    return hits


def _lexical_boost(query: str, text: str) -> float:
    q = set(query.lower().split())
    t = set(text.lower().split())
    if not q:
        return 0.0
    overlap = len(q & t) / len(q)
    return overlap * 0.35
