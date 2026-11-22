"""Post-LLM hard-rule guardrails with auditable overrides."""

from __future__ import annotations

import json
import os
import re
from typing import Any

from temporalio import activity

from temporal.models import GuardrailVerdict, ToolCall

# Tools the agent may invoke. Anything else is blocked by hard rule.
ALLOWED_TOOLS = frozenset(
    {
        "get_balance",
        "list_transactions",
        "get_kyc_status",
        "get_dispute_status",
        "search_knowledge",
        "get_document_excerpt",
    }
)

# Money-moving / write side effects are never allowed through the agent path.
BLOCKED_TOOLS = frozenset(
    {
        "transfer",
        "withdraw",
        "deposit",
        "pay",
        "payment",
        "authorize_card",
        "issue_card",
        "resolve_dispute",
        "start_dispute",
        "approve_kyc",
        "reject_kyc",
    }
)

INJECTION_PATTERNS = [
    re.compile(r"ignore\s+(all\s+)?(previous|prior|above)\s+instructions", re.I),
    re.compile(r"disregard\s+(your|the)\s+system\s+prompt", re.I),
    re.compile(r"you\s+are\s+now\s+(dan|unrestricted|jailbroken)", re.I),
]

PII_PATTERNS = [
    re.compile(r"\b(?:\d[ -]*?){13,19}\b"),  # crude PAN
    re.compile(r"\b\d{3}-\d{2}-\d{4}\b"),  # SSN-like
]


def evaluate_text(text: str) -> GuardrailVerdict:
    """Deterministic hard rules over free-text LLM output."""
    if not text:
        return GuardrailVerdict(allowed=True, overridden=False, reason="empty")

    for pattern in INJECTION_PATTERNS:
        if pattern.search(text):
            return GuardrailVerdict(
                allowed=False,
                overridden=True,
                reason="Blocked prompt-injection pattern in model output",
                hard_rule="prompt_injection",
            )

    # Never echo raw card/SSN-looking material back to the user from the model.
    for pattern in PII_PATTERNS:
        if pattern.search(text):
            return GuardrailVerdict(
                allowed=False,
                overridden=True,
                reason="Blocked potential PII in model output",
                hard_rule="pii_leakage",
            )

    lowered = text.lower()
    money_verbs = ("transfer $", "send money", "wire funds", "withdraw all", "pay the merchant")
    if any(verb in lowered for verb in money_verbs):
        return GuardrailVerdict(
            allowed=False,
            overridden=True,
            reason="Blocked money-movement instruction in model output",
            hard_rule="no_money_path",
        )

    if os.environ.get("AI_PROVIDER_MODE", "live") == "fixture" and "LIVE_PROVIDER_ONLY" in text:
        return GuardrailVerdict(
            allowed=False,
            overridden=True,
            reason="Fixture mode forbids live-provider-only markers",
            hard_rule="fixture_policy",
        )

    return GuardrailVerdict(allowed=True, overridden=False, reason="passed")


def evaluate_tool_call(call: ToolCall | dict[str, Any]) -> GuardrailVerdict:
    """Hard-rule check before any tool side effect."""
    if isinstance(call, dict):
        name = call.get("name") or call.get("function", {}).get("name", "")
        arguments = call.get("arguments") or {}
        if isinstance(arguments, str):
            try:
                arguments = json.loads(arguments)
            except json.JSONDecodeError:
                arguments = {}
    else:
        name = call.name
        arguments = call.arguments

    name = (name or "").strip()
    if name in BLOCKED_TOOLS:
        return GuardrailVerdict(
            allowed=False,
            overridden=True,
            reason=f"Tool '{name}' is blocked on the AI agent path",
            hard_rule="blocked_tool",
        )
    if name not in ALLOWED_TOOLS:
        return GuardrailVerdict(
            allowed=False,
            overridden=True,
            reason=f"Tool '{name}' is not in the allow-list",
            hard_rule="unknown_tool",
        )

    # Read-only tools must not smuggle transfer instructions in arguments.
    blob = json.dumps(arguments, default=str).lower()
    if any(token in blob for token in ("transfer", "password", "cvv", "pin")):
        return GuardrailVerdict(
            allowed=False,
            overridden=True,
            reason="Tool arguments contain forbidden fields",
            hard_rule="unsafe_args",
        )

    return GuardrailVerdict(allowed=True, overridden=False, reason="passed")


@activity.defn(name="guardrailCheck")
async def guardrail_check(payload: dict[str, Any]) -> GuardrailVerdict:
    """Activity entry: check text and/or a tool call."""
    text = payload.get("text") or ""
    text_verdict = evaluate_text(text)
    if not text_verdict.allowed:
        return text_verdict

    tool = payload.get("tool_call")
    if tool:
        return evaluate_tool_call(tool)
    return text_verdict
