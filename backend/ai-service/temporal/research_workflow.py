"""Multi-agent dispute research: parallel gather → structured draft → HITL save."""

from __future__ import annotations

import asyncio
import json
from datetime import timedelta
from typing import Any

from temporalio import workflow
from temporalio.common import RetryPolicy

with workflow.unsafe.imports_passed_through():
    from temporal.models import (
        DisputeDraft,
        DisputeResearchInput,
        DisputeResearchResult,
        GuardrailVerdict,
        LlmMessage,
        LlmRequest,
        LlmResponse,
    )
    from temporal.structured import DISPUTE_DRAFT_SCHEMA, draft_to_dict, parse_dispute_draft

RESEARCH_WORKFLOW = "disputeResearchV1"


@workflow.defn(name=RESEARCH_WORKFLOW)
class DisputeResearchWorkflow:
    """Deep-research style pipeline for dispute operator drafts."""

    @workflow.run
    async def run(self, inp: DisputeResearchInput) -> DisputeResearchResult:
        context: dict[str, Any] = await workflow.execute_activity(
            "loadDisputeContext",
            {"case_id": inp.case_id, "internal_token": ""},
            start_to_close_timeout=timedelta(seconds=15),
            retry_policy=RetryPolicy(maximum_attempts=3),
        )

        context_blob = json.dumps(context, default=str)
        # Offload a copy for callers/history references, but always feed the real
        # context text to LLM activities — never the claim key alone.
        claim = None
        if len(context_blob.encode("utf-8")) >= 2048:
            claim = await workflow.execute_activity(
                "claimCheckPut",
                context,
                result_type=str,
                start_to_close_timeout=timedelta(seconds=10),
            )
        context_for_llm = context_blob

        async def _agent(role: str, instruction: str) -> str:
            req = LlmRequest(
                provider="openai",
                messages=[
                    LlmMessage(role="system", content=instruction),
                    LlmMessage(
                        role="user",
                        content=f"Dispute case context:\n{context_for_llm}",
                    ),
                ],
                response_format=None,
                max_tokens=512,
            )
            resp = await workflow.execute_activity(
                "llmComplete",
                req,
                result_type=LlmResponse,
                start_to_close_timeout=timedelta(seconds=60),
                retry_policy=RetryPolicy(maximum_attempts=5),
            )
            verdict = await workflow.execute_activity(
                "guardrailCheck",
                {"text": resp.content or ""},
                result_type=GuardrailVerdict,
                start_to_close_timeout=timedelta(seconds=5),
            )
            if not verdict.allowed:
                return f"{role} blocked by guardrail: {verdict.reason}"
            return resp.content or ""

        ledger_notes, evidence_notes, policy_notes = await asyncio.gather(
            _agent(
                "ledger",
                "You are a ledger analyst. Summarize transfer and balance facts relevant to this dispute.",
            ),
            _agent(
                "evidence",
                "You are an evidence analyst. Summarize customer evidence and gaps for this dispute.",
            ),
            _agent(
                "policy",
                "You are a banking policy advisor. Recommend REQUEST_EVIDENCE, REFUND, DENY, ESCALATE, or NEED_MORE_INFO.",
            ),
        )

        synthesis_prompt = (
            "Synthesize a structured dispute draft from the agent notes. "
            "Return JSON matching the schema."
        )
        synthesis_body = (
            f"Ledger notes:\n{ledger_notes}\n\n"
            f"Evidence notes:\n{evidence_notes}\n\n"
            f"Policy notes:\n{policy_notes}\n\n"
            f"Original case context:\n{context_for_llm}"
        )
        synth = await workflow.execute_activity(
            "llmComplete",
            LlmRequest(
                provider="openai",
                messages=[
                    LlmMessage(role="system", content=synthesis_prompt),
                    LlmMessage(role="user", content=synthesis_body),
                ],
                response_format=DISPUTE_DRAFT_SCHEMA,
                max_tokens=800,
            ),
            result_type=LlmResponse,
            start_to_close_timeout=timedelta(seconds=60),
            retry_policy=RetryPolicy(maximum_attempts=5),
        )

        try:
            draft = parse_dispute_draft(synth.content)
        except Exception:
            draft = DisputeDraft(
                reason_code="OTHER",
                summary=synth.content[:500] or "Unable to parse structured draft",
                recommended_action="NEED_MORE_INFO",
                confidence=0.4,
                evidence_notes=evidence_notes[:500],
                ledger_notes=ledger_notes[:500],
                policy_notes=policy_notes[:500],
            )

        if not draft.ledger_notes:
            draft.ledger_notes = ledger_notes[:1000]
        if not draft.evidence_notes:
            draft.evidence_notes = evidence_notes[:1000]
        if not draft.policy_notes:
            draft.policy_notes = policy_notes[:1000]

        draft_dict = draft_to_dict(draft)
        await workflow.execute_activity(
            "saveDisputeAiDraft",
            {"case_id": inp.case_id, "draft": draft_dict, "internal_token": ""},
            start_to_close_timeout=timedelta(seconds=15),
            retry_policy=RetryPolicy(maximum_attempts=3),
        )

        return DisputeResearchResult(case_id=inp.case_id, draft=draft, conversation_claim=claim)
