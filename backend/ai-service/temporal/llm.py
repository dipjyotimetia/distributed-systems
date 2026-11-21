"""Durable LiteLLM activity with fixture mode and HTTP retry hints."""

from __future__ import annotations

import json
import os
import time
from datetime import timedelta
from typing import Any

from temporalio import activity
from temporalio.common import RetryPolicy
from temporalio.exceptions import ApplicationError

from temporal.models import LlmMessage, LlmRequest, LlmResponse

SYSTEM_PROMPT = (
    "You are a helpful banking assistant for Apex Banking. "
    "Answer questions about the user's accounts and transactions. "
    "Be concise and professional. Use tools when needed for live data. "
    "Never invent balances or transaction details."
)

PROVIDER_MODELS = {
    "anthropic": "anthropic/claude-sonnet-4-20250514",
    "openai": "openai/gpt-4o-mini",
    "gemini": "gemini/gemini-2.0-flash",
    "xai": "xai/grok-3-mini",
    "openrouter": "openrouter/mistralai/mistral-small-3.2-24b-instruct",
}

READ_ONLY_TOOLS = [
    {
        "type": "function",
        "function": {
            "name": "get_balance",
            "description": "Get the balance for an account owned by the caller.",
            "parameters": {
                "type": "object",
                "properties": {
                    "account_id": {"type": "string", "description": "Account id"},
                },
                "required": ["account_id"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "list_transactions",
            "description": "List recent transactions for the caller.",
            "parameters": {
                "type": "object",
                "properties": {
                    "account_id": {"type": "string"},
                    "limit": {"type": "integer", "default": 10},
                },
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "get_kyc_status",
            "description": "Get the caller's KYC case status.",
            "parameters": {"type": "object", "properties": {}},
        },
    },
    {
        "type": "function",
        "function": {
            "name": "get_dispute_status",
            "description": "Get dispute status by case id for the caller.",
            "parameters": {
                "type": "object",
                "properties": {
                    "case_id": {"type": "integer"},
                },
                "required": ["case_id"],
            },
        },
    },
]


def llm_retry_policy(retry_after_seconds: float | None = None) -> RetryPolicy:
    """Build a Temporal retry policy, optionally from HTTP Retry-After."""
    initial = timedelta(seconds=max(1.0, retry_after_seconds or 1.0))
    return RetryPolicy(
        initial_interval=initial,
        backoff_coefficient=2.0,
        maximum_interval=timedelta(seconds=60),
        maximum_attempts=5,
    )


def _fixture_reply(req: LlmRequest) -> LlmResponse:
    last_user = next((m.content for m in reversed(req.messages) if m.role == "user"), "")
    provider = req.provider or "openai"
    model = req.model or PROVIDER_MODELS.get(provider, "fixture-model")

    # Structured research fixtures return JSON the workflows can parse.
    if req.response_format and "json_schema" in (req.response_format or {}):
        schema_name = req.response_format.get("json_schema", {}).get("name", "")
        if schema_name == "dispute_draft":
            content = json.dumps(
                {
                    "reason_code": "UNAUTHORIZED",
                    "summary": f"Fixture draft for: {last_user[:120]}",
                    "recommended_action": "REQUEST_EVIDENCE",
                    "confidence": 0.82,
                    "evidence_notes": "Fixture evidence summary",
                    "ledger_notes": "Fixture ledger summary",
                    "policy_notes": "Fixture policy summary",
                }
            )
        elif schema_name == "agent_tool_plan":
            content = json.dumps(
                {
                    "reply": f"Fixture agent reply for: {last_user[:120]}",
                    "tool_calls": [],
                }
            )
        else:
            content = json.dumps({"summary": f"Fixture structured reply: {last_user[:80]}"})
        return LlmResponse(content=content, provider=provider, model=model, duration_ms=1)

    # Simple heuristic tool plan in fixture mode so agent loops are exercisable.
    lowered = last_user.lower()
    tool_calls: list[dict[str, Any]] = []
    if "balance" in lowered and "account" in lowered:
        # Prefer explicit account id if present as digits after "account".
        tool_calls.append(
            {
                "id": "call_fixture_balance",
                "type": "function",
                "function": {
                    "name": "get_balance",
                    "arguments": json.dumps({"account_id": "1"}),
                },
            }
        )
        content = ""
    elif "transaction" in lowered:
        tool_calls.append(
            {
                "id": "call_fixture_txns",
                "type": "function",
                "function": {
                    "name": "list_transactions",
                    "arguments": json.dumps({"limit": 5}),
                },
            }
        )
        content = ""
    elif "kyc" in lowered:
        tool_calls.append(
            {
                "id": "call_fixture_kyc",
                "type": "function",
                "function": {"name": "get_kyc_status", "arguments": "{}"},
            }
        )
        content = ""
    elif "dispute" in lowered:
        tool_calls.append(
            {
                "id": "call_fixture_dispute",
                "type": "function",
                "function": {
                    "name": "get_dispute_status",
                    "arguments": json.dumps({"case_id": 1}),
                },
            }
        )
        content = ""
    else:
        content = f"Fixture banking agent reply: {last_user[:200]}"

    return LlmResponse(
        content=content,
        provider=provider,
        model=model,
        tool_calls=tool_calls,
        duration_ms=1,
    )


def _extract_retry_after(headers: dict[str, str] | None) -> float | None:
    if not headers:
        return None
    raw = headers.get("retry-after") or headers.get("Retry-After")
    if not raw:
        return None
    try:
        return float(raw)
    except ValueError:
        return None


@activity.defn(name="llmComplete")
async def llm_complete(req: LlmRequest) -> LlmResponse:
    """Call an LLM via LiteLLM (or fixture mode). Retries honor Retry-After."""
    start = time.monotonic()
    if os.environ.get("AI_PROVIDER_MODE", "live") == "fixture":
        return _fixture_reply(req)

    try:
        import litellm
    except ImportError as exc:  # pragma: no cover - dependency present in image
        raise ApplicationError("litellm is not installed", non_retryable=True) from exc

    model = req.model or PROVIDER_MODELS.get(req.provider, PROVIDER_MODELS["openai"])
    messages = [{"role": m.role, "content": m.content} for m in req.messages]
    kwargs: dict[str, Any] = {
        "model": model,
        "messages": messages,
        "max_tokens": req.max_tokens,
    }
    if req.response_format:
        kwargs["response_format"] = req.response_format
    else:
        kwargs["tools"] = READ_ONLY_TOOLS
        kwargs["tool_choice"] = "auto"

    try:
        response = await litellm.acompletion(**kwargs)
    except Exception as exc:
        retry_after = None
        status = getattr(exc, "status_code", None)
        headers = getattr(exc, "headers", None) or getattr(exc, "response", None)
        if headers is not None and hasattr(headers, "headers"):
            retry_after = _extract_retry_after(dict(headers.headers))
        elif isinstance(headers, dict):
            retry_after = _extract_retry_after(headers)
        non_retryable = status in {400, 401, 403, 404} if status else False
        raise ApplicationError(
            f"LLM call failed: {exc}",
            non_retryable=non_retryable,
        ) from exc

    choice = response.choices[0]
    message = choice.message
    tool_calls: list[dict[str, Any]] = []
    raw_tools = getattr(message, "tool_calls", None) or []
    for call in raw_tools:
        tool_calls.append(
            {
                "id": getattr(call, "id", "") or "",
                "type": "function",
                "function": {
                    "name": call.function.name,
                    "arguments": call.function.arguments or "{}",
                },
            }
        )

    return LlmResponse(
        content=message.content or "",
        provider=req.provider,
        model=model,
        tool_calls=tool_calls,
        duration_ms=int((time.monotonic() - start) * 1000),
    )


def default_system_message() -> LlmMessage:
    return LlmMessage(role="system", content=SYSTEM_PROMPT)
