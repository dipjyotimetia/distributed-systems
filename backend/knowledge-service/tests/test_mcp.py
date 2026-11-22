"""MCP server tests via the official SDK in-memory Client."""

from __future__ import annotations

import pytest
from mcp import Client
from mcp_server import mcp


@pytest.mark.anyio
async def test_list_tools_and_call_search(monkeypatch):
    def fake_search(query: str, limit: int = 5, doc_type: str | None = None):
        return {
            "query": query,
            "hits": [
                {
                    "documentId": "00000000-0000-4000-8000-000000000001",
                    "title": "Dispute Timeframe",
                    "docType": "POLICY",
                    "score": 0.9,
                    "excerpt": "60 calendar days",
                }
            ],
        }

    monkeypatch.setattr("mcp_server._search", fake_search)

    async with Client(mcp) as client:
        tools = await client.list_tools()
        names = {tool.name for tool in tools.tools}
        assert names == {"search_knowledge", "get_document_excerpt"}

        result = await client.call_tool(
            "search_knowledge",
            {"query": "dispute timeframe", "limit": 3, "doc_type": "POLICY"},
        )
        assert result.is_error is not True
        payload = result.structured_content or {}
        if "result" in payload and isinstance(payload["result"], dict):
            payload = payload["result"]
        assert payload.get("hits")
        assert "60 calendar days" in payload["hits"][0]["excerpt"]


@pytest.mark.anyio
async def test_document_resource(monkeypatch):
    doc_id = "00000000-0000-4000-8000-000000000002"

    def fake_excerpt(document_id: str, max_chars: int = 1200):
        assert document_id == doc_id
        return {
            "documentId": document_id,
            "title": "Unauthorized Transfer",
            "excerpt": "Prefer REQUEST_EVIDENCE before REFUND",
            "contentType": "text/markdown",
            "citations": ["Unauthorized Transfer"],
        }

    monkeypatch.setattr("mcp_server._excerpt", fake_excerpt)

    async with Client(mcp) as client:
        contents = await client.read_resource(f"knowledge://document/{doc_id}")
        text = contents.contents[0].text if contents.contents else ""
        assert "REQUEST_EVIDENCE" in text
