"""
MCP server for knowledge-service using the official Python SDK.

https://github.com/modelcontextprotocol/python-sdk

Exposes read-only tools and document resources for RAG hosts.
Does not expose money-moving banking APIs.

Run (stdio):
  KNOWLEDGE_MODE=mcp uv run python -m mcp_server
  # or: uv run mcp run mcp_server.py
"""

from __future__ import annotations

import json
import os
import uuid
from typing import Any, Literal

import httpx
from mcp.server import MCPServer

KNOWLEDGE_URL = os.environ.get("KNOWLEDGE_SERVICE_URL", "http://127.0.0.1:8080").rstrip(
    "/"
)
INTERNAL_TOKEN = os.environ.get("KNOWLEDGE_INTERNAL_TOKEN", "local-knowledge-internal")

DocType = Literal["POLICY", "KYC_EVIDENCE", "DISPUTE_EVIDENCE", "STATEMENT", "OTHER"]

mcp = MCPServer(
    "banking-knowledge",
    version="1.0.0",
    instructions=(
        "Read-only Apex Banking knowledge vault. Use search_knowledge and "
        "get_document_excerpt for policy and evidence retrieval. Never attempt "
        "transfers, payments, or dispute resolution through this server."
    ),
)


def _headers() -> dict[str, str]:
    return {
        "Accept": "application/json",
        "X-Internal-Token": INTERNAL_TOKEN,
    }


def _search(query: str, limit: int = 5, doc_type: str | None = None) -> dict[str, Any]:
    payload: dict[str, Any] = {"query": query, "limit": limit}
    if doc_type:
        payload["docType"] = doc_type
    with httpx.Client(timeout=15.0) as client:
        resp = client.post(
            f"{KNOWLEDGE_URL}/api/v1/knowledge/search",
            headers=_headers(),
            json=payload,
        )
        resp.raise_for_status()
        return resp.json()


def _excerpt(document_id: str, max_chars: int = 1200) -> dict[str, Any]:
    with httpx.Client(timeout=15.0) as client:
        resp = client.get(
            f"{KNOWLEDGE_URL}/api/v1/knowledge/documents/{document_id}/excerpt",
            headers=_headers(),
            params={"maxChars": max_chars},
        )
        resp.raise_for_status()
        return resp.json()


@mcp.tool()
def search_knowledge(
    query: str,
    limit: int = 5,
    doc_type: DocType | None = None,
) -> dict[str, Any]:
    """Search bank policy and owned documents (read-only RAG)."""
    return _search(query=query, limit=limit, doc_type=doc_type)


@mcp.tool()
def get_document_excerpt(document_id: str, max_chars: int = 1200) -> dict[str, Any]:
    """Fetch a citable excerpt for a knowledge document id returned by search_knowledge."""
    return _excerpt(document_id=document_id, max_chars=max_chars)


@mcp.resource(
    "knowledge://document/{document_id}",
    mime_type="text/plain",
    description="Citable excerpt for a knowledge vault document",
)
def document_resource(document_id: str) -> str:
    """Read a knowledge document excerpt by UUID."""
    uuid.UUID(document_id)
    body = _excerpt(document_id)
    return json.dumps(body, indent=2)


def main() -> None:
    mcp.run(transport="stdio")


if __name__ == "__main__":
    main()
