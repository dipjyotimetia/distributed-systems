"""Read-only banking tool activities used by the durable agent."""

from __future__ import annotations

import asyncio
import json
import os
from typing import Any

import grpc
import httpx
from temporalio import activity

from accounts_client import ApiClient as AccountsApiClient
from accounts_client import Configuration as AccountsConfiguration
from accounts_client.api.accounts_api import AccountsApi
from accounts_client.exceptions import ApiException as AccountsApiException
from disputes_client import ApiClient as DisputesApiClient
from disputes_client import Configuration as DisputesConfiguration
from disputes_client.api.disputes_api import DisputesApi
from disputes_client.exceptions import ApiException as DisputesApiException
from gen import research_pb2, research_pb2_grpc
from transactions_client import ApiClient as TransactionsApiClient
from transactions_client import Configuration as TransactionsConfiguration
from transactions_client.api.transactions_api import TransactionsApi
from transactions_client.exceptions import ApiException as TransactionsApiException

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
    loaded = await asyncio.to_thread(_account_balance, account_id, auth_token)
    if isinstance(loaded, AccountsApiException):
        return ToolResult(name="get_balance", call_id="", content="", error=f"accounts-service returned {loaded.status}")
    return ToolResult(name="get_balance", call_id="", content=loaded)


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
    loaded = await asyncio.to_thread(_transaction_list, auth_token, account_id, limit)
    if isinstance(loaded, TransactionsApiException):
        return ToolResult(name="list_transactions", call_id="", content="", error=f"transactions-service returned {loaded.status}")
    return ToolResult(name="list_transactions", call_id="", content=loaded)


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
    loaded = await asyncio.to_thread(_dispute_case, case_id, auth_token)
    if isinstance(loaded, DisputesApiException):
        return ToolResult(name="get_dispute_status", call_id="", content="", error=f"disputes-service returned {loaded.status}")
    return ToolResult(name="get_dispute_status", call_id="", content=loaded)


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
    token = str(payload.get("internal_token") or os.environ.get("DISPUTES_INTERNAL_TOKEN", ""))
    return await asyncio.to_thread(_research_context, case_id, token)


@activity.defn(name="saveDisputeAiDraft")
async def save_dispute_ai_draft(payload: dict[str, Any]) -> dict[str, Any]:
    """Persist an AI draft on the dispute case (signals investigation workflow)."""
    case_id = int(payload.get("case_id") or 0)
    draft = dict(payload.get("draft") or {})
    if os.environ.get("AI_PROVIDER_MODE", "live") == "fixture" and not os.environ.get("AI_TOOLS_LIVE"):
        return {"id": case_id, "aiDraft": draft, "source": "fixture"}
    token = str(payload.get("internal_token") or os.environ.get("DISPUTES_INTERNAL_TOKEN", ""))
    return await asyncio.to_thread(_research_draft, case_id, token, draft)


def _dump(value: Any) -> str:
    if hasattr(value, "to_json"):
        return value.to_json()
    return json.dumps(value)


def _account_balance(account_id: str, token: str) -> str | AccountsApiException:
    api = AccountsApi(AccountsApiClient(AccountsConfiguration(host=ACCOUNTS_URL)))
    try:
        return _dump(api.get_account_balance(int(account_id), _headers=_auth_headers(token), _request_timeout=10))
    except AccountsApiException as exc:
        return exc


def _transaction_list(token: str, account_id: str | None, limit: int) -> str | TransactionsApiException:
    api = TransactionsApi(TransactionsApiClient(TransactionsConfiguration(host=TRANSACTIONS_URL)))
    try:
        body = api.list_transactions(
            account_id=int(account_id) if account_id else None,
            size=max(1, min(limit, 50)),
            _headers=_auth_headers(token),
            _request_timeout=10,
        )
        return _dump(body)
    except TransactionsApiException as exc:
        return exc


def _dispute_case(case_id: int, token: str) -> str | DisputesApiException:
    api = DisputesApi(DisputesApiClient(DisputesConfiguration(host=DISPUTES_URL)))
    try:
        return _dump(api.get_dispute(case_id, _headers=_auth_headers(token), _request_timeout=10))
    except DisputesApiException as exc:
        return exc


def _research_stub() -> research_pb2_grpc.DisputeResearchStub:
    address = os.environ.get("DISPUTES_GRPC_ADDRESS", "disputes-service:9096")
    return research_pb2_grpc.DisputeResearchStub(grpc.insecure_channel(address))


def _research_metadata(token: str) -> list[tuple[str, str]]:
    return [("x-internal-token", token)] if token else []


def _research_context(case_id: int, token: str) -> dict[str, Any]:
    reply = _research_stub().GetContext(
        research_pb2.GetContextRequest(case_id=case_id),
        metadata=_research_metadata(token),
        timeout=10,
    )
    return json.loads(reply.json)


def _research_draft(case_id: int, token: str, draft: dict[str, Any]) -> dict[str, Any]:
    reply = _research_stub().SaveDraft(
        research_pb2.SaveDraftRequest(case_id=case_id, draft_json=json.dumps(draft)),
        metadata=_research_metadata(token),
        timeout=10,
    )
    return json.loads(reply.json)
