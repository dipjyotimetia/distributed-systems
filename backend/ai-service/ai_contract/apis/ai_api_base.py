# coding: utf-8

from typing import ClassVar, Dict, List, Tuple  # noqa: F401

from pydantic import StrictStr
from typing import Any, Dict, List, Optional
from ai_contract.models.agent_chat_request import AgentChatRequest
from ai_contract.models.chat_request import ChatRequest
from ai_contract.models.chat_response import ChatResponse
from ai_contract.models.dispute_research_request import DisputeResearchRequest
from ai_contract.models.health import Health
from ai_contract.models.provider import Provider


class BaseAiApi:
    subclasses: ClassVar[Tuple] = ()

    def __init_subclass__(cls, **kwargs):
        super().__init_subclass__(**kwargs)
        BaseAiApi.subclasses = BaseAiApi.subclasses + (cls,)
    async def agent_chat(
        self,
        agent_chat_request: AgentChatRequest,
        authorization: Optional[StrictStr],
    ) -> Dict[str, object]:
        ...


    async def ai_health(
        self,
    ) -> Health:
        ...


    async def chat(
        self,
        chat_request: ChatRequest,
    ) -> ChatResponse:
        ...


    async def dispute_research(
        self,
        dispute_research_request: DisputeResearchRequest,
        x_internal_token: Optional[StrictStr],
    ) -> Dict[str, object]:
        ...


    async def list_providers(
        self,
    ) -> List[Provider]:
        ...
