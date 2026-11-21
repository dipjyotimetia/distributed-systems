# coding: utf-8

from typing import Dict, List  # noqa: F401
import importlib
import pkgutil

from ai_contract.apis.ai_api_base import BaseAiApi
import ai_contract.impl

from fastapi import (  # noqa: F401
    APIRouter,
    Body,
    Cookie,
    Depends,
    Form,
    Header,
    HTTPException,
    Path,
    Query,
    Response,
    Security,
    status,
)

from ai_contract.models.extra_models import TokenModel  # noqa: F401
from pydantic import StrictStr
from typing import Any, Dict, List, Optional
from ai_contract.models.agent_chat_request import AgentChatRequest
from ai_contract.models.chat_request import ChatRequest
from ai_contract.models.chat_response import ChatResponse
from ai_contract.models.dispute_research_request import DisputeResearchRequest
from ai_contract.models.health import Health
from ai_contract.models.provider import Provider


router = APIRouter()

ns_pkg = ai_contract.impl
for _, name, _ in pkgutil.iter_modules(ns_pkg.__path__, ns_pkg.__name__ + "."):
    importlib.import_module(name)


@router.post(
    "/api/v1/agent/chat",
    responses={
        200: {"model": Dict[str, object], "description": "Workflow result or handle"},
        503: {"description": "Agent workflow unavailable"},
    },
    tags=["ai"],
    summary="Start a durable banking agent workflow",
    response_model_by_alias=True,
)
async def agent_chat(
    agent_chat_request: AgentChatRequest = Body(None, description=""),
    authorization: Optional[StrictStr] = Header(None, description=""),
) -> Dict[str, object]:
    if not BaseAiApi.subclasses:
        raise HTTPException(status_code=500, detail="Not implemented")
    return await BaseAiApi.subclasses[0]().agent_chat(agent_chat_request, authorization)


@router.get(
    "/health",
    responses={
        200: {"model": Health, "description": "Healthy"},
    },
    tags=["ai"],
    summary="Liveness",
    response_model_by_alias=True,
)
async def ai_health(
) -> Health:
    if not BaseAiApi.subclasses:
        raise HTTPException(status_code=500, detail="Not implemented")
    return await BaseAiApi.subclasses[0]().ai_health()


@router.post(
    "/api/v1/chat",
    responses={
        200: {"model": ChatResponse, "description": "Provider replies"},
    },
    tags=["ai"],
    summary="Ask every configured model",
    response_model_by_alias=True,
)
async def chat(
    chat_request: ChatRequest = Body(None, description=""),
) -> ChatResponse:
    if not BaseAiApi.subclasses:
        raise HTTPException(status_code=500, detail="Not implemented")
    return await BaseAiApi.subclasses[0]().chat(chat_request)


@router.post(
    "/api/v1/disputes/research",
    responses={
        200: {"model": Dict[str, object], "description": "Research result or handle"},
        401: {"description": "Internal token required"},
        503: {"description": "Dispute research unavailable"},
    },
    tags=["ai"],
    summary="Start multi-agent dispute research",
    response_model_by_alias=True,
)
async def dispute_research(
    dispute_research_request: DisputeResearchRequest = Body(None, description=""),
    x_internal_token: Optional[StrictStr] = Header(None, description=""),
) -> Dict[str, object]:
    if not BaseAiApi.subclasses:
        raise HTTPException(status_code=500, detail="Not implemented")
    return await BaseAiApi.subclasses[0]().dispute_research(dispute_research_request, x_internal_token)


@router.get(
    "/api/v1/providers",
    responses={
        200: {"model": List[Provider], "description": "Providers"},
    },
    tags=["ai"],
    summary="List configured model providers",
    response_model_by_alias=True,
)
async def list_providers(
) -> List[Provider]:
    if not BaseAiApi.subclasses:
        raise HTTPException(status_code=500, detail="Not implemented")
    return await BaseAiApi.subclasses[0]().list_providers()
