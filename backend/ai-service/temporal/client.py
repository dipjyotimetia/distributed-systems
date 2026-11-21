"""Temporal client helpers used by the FastAPI process."""

from __future__ import annotations

import os
import uuid
from typing import Any

from temporalio.client import Client

from temporal import AGENT_WORKFLOW, RESEARCH_WORKFLOW, TASK_QUEUE
from temporal.models import AgentInput, AgentResult, DisputeResearchInput, DisputeResearchResult

_client: Client | None = None


async def get_client() -> Client:
    global _client
    if _client is None:
        address = os.environ.get("TEMPORAL_ADDRESS", "localhost:7233")
        namespace = os.environ.get("TEMPORAL_NAMESPACE", "default")
        _client = await Client.connect(address, namespace=namespace)
    return _client


async def start_banking_agent(inp: AgentInput, wait: bool = True) -> dict[str, Any]:
    client = await get_client()
    workflow_id = f"banking-agent-{uuid.uuid4()}"
    handle = await client.start_workflow(
        AGENT_WORKFLOW,
        inp,
        id=workflow_id,
        task_queue=TASK_QUEUE,
    )
    if not wait:
        return {"workflowId": workflow_id, "runId": handle.result_run_id}
    result: AgentResult = await handle.result()
    return {
        "workflowId": workflow_id,
        "reply": result.reply,
        "provider": result.provider,
        "model": result.model,
        "toolTrace": result.tool_trace,
        "conversationClaim": result.conversation_claim,
        "blocked": result.blocked,
        "blockReason": result.block_reason,
    }


async def start_dispute_research(inp: DisputeResearchInput, wait: bool = True) -> dict[str, Any]:
    client = await get_client()
    workflow_id = f"dispute-ai-{inp.case_id}"
    handle = await client.start_workflow(
        RESEARCH_WORKFLOW,
        inp,
        id=workflow_id,
        task_queue=TASK_QUEUE,
    )
    if not wait:
        return {"workflowId": workflow_id, "runId": handle.result_run_id}
    result: DisputeResearchResult = await handle.result()
    draft = result.draft
    return {
        "workflowId": workflow_id,
        "caseId": result.case_id,
        "draft": {
            "reasonCode": draft.reason_code,
            "summary": draft.summary,
            "recommendedAction": draft.recommended_action,
            "confidence": draft.confidence,
            "evidenceNotes": draft.evidence_notes,
            "ledgerNotes": draft.ledger_notes,
            "policyNotes": draft.policy_notes,
        },
        "conversationClaim": result.conversation_claim,
    }
