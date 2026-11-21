import json

import pytest

from temporal.tools import execute_tool, load_dispute_context, save_dispute_ai_draft


@pytest.mark.asyncio
async def test_execute_tool_balance_fixture(monkeypatch):
    monkeypatch.setenv("AI_PROVIDER_MODE", "fixture")
    monkeypatch.delenv("AI_TOOLS_LIVE", raising=False)
    result = await execute_tool(
        {"name": "get_balance", "arguments": {"account_id": "42"}, "call_id": "c1"}
    )
    body = json.loads(result.content)
    assert body["accountId"] == "42"
    assert body["source"] == "fixture"
    assert result.call_id == "c1"


@pytest.mark.asyncio
async def test_dispute_context_and_draft_fixture(monkeypatch):
    monkeypatch.setenv("AI_PROVIDER_MODE", "fixture")
    monkeypatch.delenv("AI_TOOLS_LIVE", raising=False)
    ctx = await load_dispute_context({"case_id": 7})
    assert ctx["id"] == 7
    saved = await save_dispute_ai_draft(
        {"case_id": 7, "draft": {"summary": "ok", "reasonCode": "OTHER"}}
    )
    assert saved["aiDraft"]["summary"] == "ok"
