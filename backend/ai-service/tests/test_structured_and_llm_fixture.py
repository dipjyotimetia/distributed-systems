import json
import os

import pytest

from temporal.llm import llm_complete
from temporal.models import LlmMessage, LlmRequest
from temporal.structured import DISPUTE_DRAFT_SCHEMA, parse_dispute_draft


@pytest.mark.asyncio
async def test_fixture_llm_structured_dispute_draft(monkeypatch):
    monkeypatch.setenv("AI_PROVIDER_MODE", "fixture")
    resp = await llm_complete(
        LlmRequest(
            messages=[LlmMessage(role="user", content="Unauthorized charge")],
            response_format=DISPUTE_DRAFT_SCHEMA,
        )
    )
    draft = parse_dispute_draft(resp.content)
    assert draft.reason_code == "UNAUTHORIZED"
    assert draft.recommended_action == "REQUEST_EVIDENCE"
    assert draft.confidence > 0


@pytest.mark.asyncio
async def test_fixture_llm_balance_tool_plan(monkeypatch):
    monkeypatch.setenv("AI_PROVIDER_MODE", "fixture")
    resp = await llm_complete(
        LlmRequest(messages=[LlmMessage(role="user", content="What is my account balance?")])
    )
    assert resp.tool_calls
    assert resp.tool_calls[0]["function"]["name"] == "get_balance"
