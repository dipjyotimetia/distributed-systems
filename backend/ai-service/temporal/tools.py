"""Read-only banking tool activities used by the durable agent."""

from __future__ import annotations

import json
import os
from typing import Any

import httpx
from temporalio import activity

from temporal.models import ToolCall, ToolResult

ACCOUNTS_URL = os.environ.get("ACCOUNTS_SERVICE_URL", "http://accounts-service:8080").rstrip("/")
TRANSACTIONS_URL = os.environ.get("TRANSACTIONS_SERVICE_URL", "http://transactions-service:8080").rstrip("/")
DISPUTES_URL = os.environ.get("DISPUTES_SERVICE_URL", "http://disputes-service:8080").rstrip("/")
KYC_HTTP_URL = os.environ.get("KYC_STATUS_URL", "").rstrip("/")  # optional HTTP facade
FRONTEND_KYC_URL = os.environ.get("KYC_PROXY_URL", "").rstrip("/")


def _auth_headers(token: str) -> dict[str, str]:
    headers = {"Accept": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    return headers


@activity.defn(name="getBalance")
async def get_balance(account_id: str, auth_token: str = "") -> ToolResult:
    if os.environ.get("AI_PROVIDER_MODE", "live") == "fixture" and not os.environ.get("AI_TOOLS_LIVE"):
        return ToolResult(
            name="get_balance",
            call_id="",
            content=json.dumps(
                {
                    "accountId": account_id,
                    "balance": 1234.56,
                    "currency": "USD",
                    "source": "fixture",
                }
            ),
        )
    async with httpx.AsyncClient(timeout=10.0) as client:
        resp = await client.get(
            f"{ACCOUNTS_URL}/api/accounts/{account_id}/balance",
            headers=_auth_headers(auth_token),
        )
        if resp.status_code >= 400:
            return ToolResult(
                name="get_balance",
                call_id="",
                content="",
                error=f"accounts-service returned {resp.status_code}",
            )
        return ToolResult(name="get_balance", call_id="", content=resp.text)


@activity.defn(name="listTransactions")
async def list_transactions(
    auth_token: str = "",
    account_id: str | None = None,
    limit: int = 10,
) -> ToolResult:
    if os.environ.get("AI_PROVIDER_MODE", "live") == "fixture" and not os.environ.get("AI_TOOLS_LIVE"):
        return ToolResult(
            name="list_transactions",
            call_id="",
            content=json.dumps(
                {
                    "items": [
                        {
                            "id": 101,
                            "accountId": account_id or "1",
                            "amount": -42.50,
                            "type": "PURCHASE",
                            "status": "SETTLED",
                        }
                    ],
                    "source": "fixture",
                }
            ),
        )
    params: dict[str, Any] = {"limit": max(1, min(limit, 50))}
    if account_id:
        params["accountId"] = account_id
    async with httpx.AsyncClient(timeout=10.0) as client:
        resp = await client.get(
            f"{TRANSACTIONS_URL}/api/transactions",
            headers=_auth_headers(auth_token),
            params=params,
        )
        if resp.status_code >= 400:
            return ToolResult(
                name="list_transactions",
                call_id="",
                content="",
                error=f"transactions-service returned {resp.status_code}",
            )
        return ToolResult(name="list_transactions", call_id="", content=resp.text)


@activity.defn(name="getKycStatus")
async def get_kyc_status(auth_token: str = "") -> ToolResult:
    if os.environ.get("AI_PROVIDER_MODE", "live") == "fixture" and not os.environ.get("AI_TOOLS_LIVE"):
        return ToolResult(
            name="get_kyc_status",
            call_id="",
            content=json.dumps({"status": "APPROVED", "source": "fixture"}),
        )
    # Prefer an explicit HTTP proxy if configured (frontend/gateway); otherwise fixture-like stub.
    url = KYC_HTTP_URL or FRONTEND_KYC_URL
    if not url:
        return ToolResult(
            name="get_kyc_status",
            call_id="",
            content=json.dumps(
                {
                    "status": "UNKNOWN",
                    "note": "KYC_STATUS_URL not configured; gRPC KYC is frontend-only",
                }
            ),
        )
    async with httpx.AsyncClient(timeout=10.0) as client:
        resp = await client.get(url, headers=_auth_headers(auth_token))
        if resp.status_code >= 400:
            return ToolResult(
                name="get_kyc_status",
                call_id="",
                content="",
                error=f"kyc status returned {resp.status_code}",
            )
        return ToolResult(name="get_kyc_status", call_id="", content=resp.text)


@activity.defn(name="getDisputeStatus")
async def get_dispute_status(case_id: int, auth_token: str = "") -> ToolResult:
    if os.environ.get("AI_PROVIDER_MODE", "live") == "fixture" and not os.environ.get("AI_TOOLS_LIVE"):
        return ToolResult(
            name="get_dispute_status",
            call_id="",
            content=json.dumps(
                {
                    "id": case_id,
                    "status": "OPEN",
                    "investigationStage": "OPERATOR_REVIEW",
                    "source": "fixture",
                }
            ),
        )
    async with httpx.AsyncClient(timeout=10.0) as client:
        resp = await client.get(
            f"{DISPUTES_URL}/api/disputes/{case_id}",
            headers=_auth_headers(auth_token),
        )
        if resp.status_code >= 400:
            return ToolResult(
                name="get_dispute_status",
                call_id="",
                content="",
                error=f"disputes-service returned {resp.status_code}",
            )
        return ToolResult(name="get_dispute_status", call_id="", content=resp.text)


@activity.defn(name="executeTool")
async def execute_tool(payload: dict[str, Any]) -> ToolResult:
    """Dispatch a single allow-listed tool call."""
    call = ToolCall(
        name=str(payload.get("name") or ""),
        arguments=dict(payload.get("arguments") or {}),
        call_id=str(payload.get("call_id") or ""),
    )
    auth_token = str(payload.get("auth_token") or "")
    name = call.name
    args = call.arguments or {}
    if name == "get_balance":
        result = await get_balance(str(args.get("account_id", "")), auth_token)
    elif name == "list_transactions":
        result = await list_transactions(
            auth_token=auth_token,
            account_id=str(args["account_id"]) if args.get("account_id") is not None else None,
            limit=int(args.get("limit") or 10),
        )
    elif name == "get_kyc_status":
        result = await get_kyc_status(auth_token)
    elif name == "get_dispute_status":
        result = await get_dispute_status(int(args.get("case_id") or 0), auth_token)
    else:
        return ToolResult(name=name, call_id=call.call_id, content="", error=f"unknown tool {name}")
    result.call_id = call.call_id
    return result


@activity.defn(name="loadDisputeContext")
async def load_dispute_context(payload: dict[str, Any]) -> dict[str, Any]:
    """Load dispute case context for multi-agent research via internal API."""
    case_id = int(payload.get("case_id") or 0)
    if os.environ.get("AI_PROVIDER_MODE", "live") == "fixture" and not os.environ.get("AI_TOOLS_LIVE"):
        return {
            "id": case_id,
            "transferId": 9001,
            "reason": "Customer reports unauthorized transfer",
            "status": "ESCALATED",
            "investigationStage": "OPERATOR_REVIEW",
            "evidenceRequest": {
                "prompt": "Please describe the unrecognized merchant",
                "response": "I never shopped at that store",
            },
            "aiDraft": None,
            "source": "fixture",
        }
    headers = {"Accept": "application/json"}
    token = str(payload.get("internal_token") or os.environ.get("DISPUTES_INTERNAL_TOKEN", ""))
    if token:
        headers["X-Internal-Token"] = token
    async with httpx.AsyncClient(timeout=10.0) as client:
        resp = await client.get(
            f"{DISPUTES_URL}/internal/disputes/{case_id}/context",
            headers=headers,
        )
        resp.raise_for_status()
        return resp.json()


@activity.defn(name="saveDisputeAiDraft")
async def save_dispute_ai_draft(payload: dict[str, Any]) -> dict[str, Any]:
    """Persist an AI draft on the dispute case (signals investigation workflow)."""
    case_id = int(payload.get("case_id") or 0)
    draft = dict(payload.get("draft") or {})
    if os.environ.get("AI_PROVIDER_MODE", "live") == "fixture" and not os.environ.get("AI_TOOLS_LIVE"):
        return {"id": case_id, "aiDraft": draft, "source": "fixture"}
    headers = {"Content-Type": "application/json", "Accept": "application/json"}
    token = str(payload.get("internal_token") or os.environ.get("DISPUTES_INTERNAL_TOKEN", ""))
    if token:
        headers["X-Internal-Token"] = token
    async with httpx.AsyncClient(timeout=10.0) as client:
        resp = await client.post(
            f"{DISPUTES_URL}/internal/disputes/{case_id}/ai-draft",
            headers=headers,
            json={"draft": draft},
        )
        resp.raise_for_status()
        return resp.json()
