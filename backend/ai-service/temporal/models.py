"""Shared dataclasses for Temporal AI workflows."""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any


@dataclass
class LlmMessage:
    role: str
    content: str


@dataclass
class LlmRequest:
    messages: list[LlmMessage]
    provider: str = "openai"
    model: str | None = None
    response_format: dict[str, Any] | None = None
    max_tokens: int = 1024


@dataclass
class LlmResponse:
    content: str
    provider: str
    model: str
    tool_calls: list[dict[str, Any]] = field(default_factory=list)
    duration_ms: int = 0
    claim_key: str | None = None


@dataclass
class GuardrailVerdict:
    allowed: bool
    overridden: bool
    reason: str
    hard_rule: str | None = None


@dataclass
class ToolCall:
    name: str
    arguments: dict[str, Any]
    call_id: str = ""


@dataclass
class ToolResult:
    name: str
    call_id: str
    content: str
    error: str | None = None


@dataclass
class AgentInput:
    message: str
    auth_token: str = ""
    account_id: str | None = None
    locale: str = "en-US"
    conversation_claim: str | None = None
    max_turns: int = 6


@dataclass
class AgentResult:
    reply: str
    provider: str
    model: str
    tool_trace: list[dict[str, Any]] = field(default_factory=list)
    conversation_claim: str | None = None
    blocked: bool = False
    block_reason: str | None = None


@dataclass
class DisputeResearchInput:
    case_id: int
    auth_token: str = ""
    locale: str = "en-US"


@dataclass
class DisputeDraft:
    reason_code: str
    summary: str
    recommended_action: str
    confidence: float
    evidence_notes: str = ""
    ledger_notes: str = ""
    policy_notes: str = ""


@dataclass
class DisputeResearchResult:
    case_id: int
    draft: DisputeDraft
    conversation_claim: str | None = None
