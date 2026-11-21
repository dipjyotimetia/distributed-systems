"""Durable banking agent with tool calling, guardrails, and claim-check history."""

from __future__ import annotations

import json
from datetime import timedelta
from typing import Any

from temporalio import workflow
from temporalio.common import RetryPolicy

with workflow.unsafe.imports_passed_through():
    from temporal.models import (
        AgentInput,
        AgentResult,
        GuardrailVerdict,
        LlmMessage,
        LlmRequest,
        LlmResponse,
        ToolCall,
        ToolResult,
    )

AGENT_WORKFLOW = "bankingAgentV1"


@workflow.defn(name=AGENT_WORKFLOW)
class BankingAgentWorkflow:
    """Agentic loop: LLM → guardrail → tools → repeat until final reply."""

    @workflow.run
    async def run(self, inp: AgentInput) -> AgentResult:
        messages: list[LlmMessage] = [
            LlmMessage(
                role="system",
                content=(
                    "You are Apex Banking's durable assistant. "
                    "Use read-only tools for live data. "
                    "Never transfer money, approve KYC, or resolve disputes."
                ),
            )
        ]
        if inp.conversation_claim:
            prior = await workflow.execute_activity(
                "claimCheckGet",
                inp.conversation_claim,
                start_to_close_timeout=timedelta(seconds=10),
            )
            for item in prior:
                messages.append(LlmMessage(role=item["role"], content=item["content"]))

        messages.append(LlmMessage(role="user", content=inp.message))
        tool_trace: list[dict[str, Any]] = []
        provider = "openai"
        model = "fixture-model"
        reply = ""

        for _ in range(max(1, inp.max_turns)):
            llm_resp = await workflow.execute_activity(
                "llmComplete",
                LlmRequest(messages=messages, provider=provider),
                result_type=LlmResponse,
                start_to_close_timeout=timedelta(seconds=60),
                retry_policy=RetryPolicy(maximum_attempts=5),
            )
            provider = llm_resp.provider
            model = llm_resp.model

            text_verdict = await workflow.execute_activity(
                "guardrailCheck",
                {"text": llm_resp.content or ""},
                result_type=GuardrailVerdict,
                start_to_close_timeout=timedelta(seconds=5),
            )
            if not text_verdict.allowed:
                return AgentResult(
                    reply=(
                        "I can't help with that request. "
                        f"({text_verdict.reason})"
                    ),
                    provider=provider,
                    model=model,
                    tool_trace=tool_trace,
                    blocked=True,
                    block_reason=text_verdict.hard_rule or text_verdict.reason,
                )

            if not llm_resp.tool_calls:
                reply = llm_resp.content or "I don't have additional details."
                messages.append(LlmMessage(role="assistant", content=reply))
                break

            messages.append(
                LlmMessage(
                    role="assistant",
                    content=llm_resp.content or json.dumps(llm_resp.tool_calls),
                )
            )

            for raw_call in llm_resp.tool_calls:
                fn = raw_call.get("function") or {}
                name = fn.get("name") or raw_call.get("name") or ""
                args_raw = fn.get("arguments") or raw_call.get("arguments") or "{}"
                try:
                    args = json.loads(args_raw) if isinstance(args_raw, str) else dict(args_raw)
                except json.JSONDecodeError:
                    args = {}
                call = ToolCall(name=name, arguments=args, call_id=raw_call.get("id") or "")

                tool_verdict = await workflow.execute_activity(
                    "guardrailCheck",
                    {
                        "text": "",
                        "tool_call": {"name": call.name, "arguments": call.arguments},
                    },
                    result_type=GuardrailVerdict,
                    start_to_close_timeout=timedelta(seconds=5),
                )
                if not tool_verdict.allowed:
                    tool_trace.append(
                        {
                            "tool": call.name,
                            "blocked": True,
                            "reason": tool_verdict.reason,
                        }
                    )
                    return AgentResult(
                        reply=(
                            "I can only use read-only banking tools. "
                            f"Blocked: {tool_verdict.reason}"
                        ),
                        provider=provider,
                        model=model,
                        tool_trace=tool_trace,
                        blocked=True,
                        block_reason=tool_verdict.hard_rule or tool_verdict.reason,
                    )

                result = await workflow.execute_activity(
                    "executeTool",
                    {
                        "name": call.name,
                        "arguments": call.arguments,
                        "call_id": call.call_id,
                        "auth_token": inp.auth_token,
                    },
                    result_type=ToolResult,
                    start_to_close_timeout=timedelta(seconds=20),
                    retry_policy=RetryPolicy(maximum_attempts=3),
                )
                tool_trace.append(
                    {
                        "tool": result.name,
                        "error": result.error,
                        "preview": (result.content or "")[:240],
                    }
                )
                messages.append(
                    LlmMessage(
                        role="user",
                        content=(
                            f"Tool {result.name} result: "
                            + (result.error or result.content or "")
                        ),
                    )
                )
        else:
            reply = reply or "I reached my tool-step limit. Please refine your question."

        claim = None
        history = [{"role": m.role, "content": m.content} for m in messages]
        encoded_size = len(json.dumps(history).encode("utf-8"))
        if encoded_size >= 2048:
            claim = await workflow.execute_activity(
                "claimCheckPut",
                history,
                result_type=str,
                start_to_close_timeout=timedelta(seconds=10),
            )

        return AgentResult(
            reply=reply,
            provider=provider,
            model=model,
            tool_trace=tool_trace,
            conversation_claim=claim,
        )
