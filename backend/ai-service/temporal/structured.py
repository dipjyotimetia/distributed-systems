"""Structured-output JSON schemas for typed LLM results."""

from __future__ import annotations

import json
from typing import Any

from temporal.models import DisputeDraft

DISPUTE_DRAFT_SCHEMA: dict[str, Any] = {
    "type": "json_schema",
    "json_schema": {
        "name": "dispute_draft",
        "strict": True,
        "schema": {
            "type": "object",
            "properties": {
                "reason_code": {
                    "type": "string",
                    "enum": [
                        "UNAUTHORIZED",
                        "DUPLICATE",
                        "NOT_AS_DESCRIBED",
                        "MERCHANT_ERROR",
                        "OTHER",
                    ],
                },
                "summary": {"type": "string"},
                "recommended_action": {
                    "type": "string",
                    "enum": [
                        "REQUEST_EVIDENCE",
                        "REFUND",
                        "DENY",
                        "ESCALATE",
                        "NEED_MORE_INFO",
                    ],
                },
                "confidence": {"type": "number"},
                "evidence_notes": {"type": "string"},
                "ledger_notes": {"type": "string"},
                "policy_notes": {"type": "string"},
            },
            "required": [
                "reason_code",
                "summary",
                "recommended_action",
                "confidence",
                "evidence_notes",
                "ledger_notes",
                "policy_notes",
            ],
            "additionalProperties": False,
        },
    },
}


def parse_dispute_draft(raw: str) -> DisputeDraft:
    data = json.loads(raw)
    return DisputeDraft(
        reason_code=str(data.get("reason_code", "OTHER")),
        summary=str(data.get("summary", "")),
        recommended_action=str(data.get("recommended_action", "NEED_MORE_INFO")),
        confidence=float(data.get("confidence", 0.0)),
        evidence_notes=str(data.get("evidence_notes", "")),
        ledger_notes=str(data.get("ledger_notes", "")),
        policy_notes=str(data.get("policy_notes", "")),
    )


def draft_to_dict(draft: DisputeDraft) -> dict[str, Any]:
    return {
        "reasonCode": draft.reason_code,
        "summary": draft.summary,
        "recommendedAction": draft.recommended_action,
        "confidence": draft.confidence,
        "evidenceNotes": draft.evidence_notes,
        "ledgerNotes": draft.ledger_notes,
        "policyNotes": draft.policy_notes,
    }
