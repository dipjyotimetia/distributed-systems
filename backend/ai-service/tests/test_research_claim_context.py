"""Ensure dispute research feeds real context text to the LLM, not claim keys."""

import pytest
from temporalio import activity
from temporalio.testing import WorkflowEnvironment
from temporalio.worker import Worker

from temporal.claim_check import claim_check_get, claim_check_put
from temporal.guardrails import guardrail_check
from temporal.llm import llm_complete
from temporal.models import DisputeResearchInput, LlmRequest, LlmResponse
from temporal.research_workflow import DisputeResearchWorkflow
from temporal.tools import save_dispute_ai_draft


@pytest.mark.asyncio
async def test_research_uses_real_context_when_claim_check_triggers(monkeypatch, tmp_path):
    monkeypatch.setenv("AI_PROVIDER_MODE", "fixture")
    monkeypatch.setenv("CLAIM_CHECK_DIR", str(tmp_path))

    seen_prompts: list[str] = []

    @activity.defn(name="loadDisputeContext")
    async def large_context(_payload: dict) -> dict:
        return {
            "id": 99,
            "reason": "Customer reports unauthorized transfer",
            "status": "ESCALATED",
            "evidenceRequest": {"prompt": "why", "response": "never shopped there"},
            "blob": "x" * 3000,
        }

    @activity.defn(name="llmComplete")
    async def capturing_llm(req: LlmRequest) -> LlmResponse:
        seen_prompts.append(" ".join(m.content for m in req.messages))
        return await llm_complete(req)

    async with await WorkflowEnvironment.start_time_skipping() as env:
        async with Worker(
            env.client,
            task_queue="test-ai",
            workflows=[DisputeResearchWorkflow],
            activities=[
                capturing_llm,
                guardrail_check,
                large_context,
                save_dispute_ai_draft,
                claim_check_put,
                claim_check_get,
            ],
        ):
            result = await env.client.execute_workflow(
                DisputeResearchWorkflow.run,
                DisputeResearchInput(case_id=99),
                id="research-claim-99",
                task_queue="test-ai",
            )

    assert result.case_id == 99
    assert result.conversation_claim  # claim-check fired for the large payload
    assert seen_prompts
    assert all("claim:" not in prompt for prompt in seen_prompts)
    assert any("unauthorized transfer" in prompt.lower() for prompt in seen_prompts)
