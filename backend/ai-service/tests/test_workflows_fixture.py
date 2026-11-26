"""Workflow tests against the Temporal time-skipping test environment."""

import os

import pytest
from temporalio import activity
from temporalio.testing import WorkflowEnvironment
from temporalio.worker import Worker

from temporal.agent_workflow import BankingAgentWorkflow
from temporal.guardrails import guardrail_check
from temporal.llm import llm_complete
from temporal.models import AgentInput, DisputeResearchInput
from temporal.research_workflow import DisputeResearchWorkflow
from temporal.tools import execute_tool, load_dispute_context, save_dispute_ai_draft, search_knowledge
from temporal.claim_check import claim_check_get, claim_check_put


@pytest.fixture(autouse=True)
def _fixture_mode(monkeypatch, tmp_path):
    monkeypatch.setenv("AI_PROVIDER_MODE", "fixture")
    monkeypatch.delenv("AI_TOOLS_LIVE", raising=False)
    monkeypatch.setenv("CLAIM_CHECK_DIR", str(tmp_path))


@pytest.mark.asyncio
async def test_banking_agent_workflow_blocks_transfer_tool(monkeypatch):
    @activity.defn(name="llmComplete")
    async def llm_transfer(_req):
        from temporal.models import LlmResponse

        return LlmResponse(
            content="",
            provider="fixture",
            model="fixture",
            tool_calls=[
                {
                    "id": "x",
                    "type": "function",
                    "function": {
                        "name": "transfer",
                        "arguments": '{"amount": 10}',
                    },
                }
            ],
        )

    async with await WorkflowEnvironment.start_time_skipping() as env:
        async with Worker(
            env.client,
            task_queue="test-ai",
            workflows=[BankingAgentWorkflow],
            activities=[llm_transfer, guardrail_check, execute_tool, claim_check_put, claim_check_get],
        ):
            result = await env.client.execute_workflow(
                BankingAgentWorkflow.run,
                AgentInput(message="send money"),
                id="agent-block-1",
                task_queue="test-ai",
            )
    assert result.blocked
    assert result.block_reason in {"blocked_tool", "unknown_tool"}


@pytest.mark.asyncio
async def test_banking_agent_workflow_happy_path():
    async with await WorkflowEnvironment.start_time_skipping() as env:
        async with Worker(
            env.client,
            task_queue="test-ai",
            workflows=[BankingAgentWorkflow],
            activities=[
                llm_complete,
                guardrail_check,
                execute_tool,
                claim_check_put,
                claim_check_get,
            ],
        ):
            result = await env.client.execute_workflow(
                BankingAgentWorkflow.run,
                AgentInput(message="Hello, what can you help with?"),
                id="agent-ok-1",
                task_queue="test-ai",
            )
    assert not result.blocked
    assert "Fixture banking agent reply" in result.reply


@pytest.mark.asyncio
async def test_dispute_research_workflow_saves_structured_draft():
    async with await WorkflowEnvironment.start_time_skipping() as env:
        async with Worker(
            env.client,
            task_queue="test-ai",
            workflows=[DisputeResearchWorkflow],
            activities=[
                llm_complete,
                guardrail_check,
                execute_tool,
                search_knowledge,
                load_dispute_context,
                save_dispute_ai_draft,
                claim_check_put,
                claim_check_get,
            ],
        ):
            result = await env.client.execute_workflow(
                DisputeResearchWorkflow.run,
                DisputeResearchInput(case_id=55),
                id="research-55",
                task_queue="test-ai",
            )
    assert result.case_id == 55
    assert result.draft.reason_code == "UNAUTHORIZED"
    assert result.draft.recommended_action == "REQUEST_EVIDENCE"
