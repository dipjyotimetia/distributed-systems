"""Deterministic fixture embeddings (no live LLM keys required)."""

from __future__ import annotations

import hashlib
import math
import re

DIM = 64
_TOKEN = re.compile(r"[a-z0-9]{2,}")


def tokenize(text: str) -> list[str]:
    return _TOKEN.findall(text.lower())


def embed_text(text: str) -> list[float]:
    """Hashing bag-of-words embedding — stable across processes for fixture RAG."""
    vec = [0.0] * DIM
    tokens = tokenize(text)
    if not tokens:
        return vec
    for token in tokens:
        digest = hashlib.sha256(token.encode("utf-8")).digest()
        idx = int.from_bytes(digest[:2], "big") % DIM
        sign = 1.0 if digest[2] % 2 == 0 else -1.0
        vec[idx] += sign
    norm = math.sqrt(sum(v * v for v in vec)) or 1.0
    return [v / norm for v in vec]


def cosine(a: list[float], b: list[float]) -> float:
    return sum(x * y for x, y in zip(a, b, strict=False))


def chunk_text(text: str, size: int = 500, overlap: int = 80) -> list[str]:
    cleaned = text.strip()
    if not cleaned:
        return [""]
    if len(cleaned) <= size:
        return [cleaned]
    chunks: list[str] = []
    start = 0
    while start < len(cleaned):
        end = min(len(cleaned), start + size)
        chunks.append(cleaned[start:end])
        if end >= len(cleaned):
            break
        start = max(end - overlap, start + 1)
    return chunks
