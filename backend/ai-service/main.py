import asyncio
import logging
import os
import time

import httpx
from fastapi import FastAPI, Request, Response
from fastapi.middleware.cors import CORSMiddleware
from opentelemetry import trace
from opentelemetry.exporter.otlp.proto.http.trace_exporter import OTLPSpanExporter
from opentelemetry.instrumentation.fastapi import FastAPIInstrumentor
from opentelemetry.instrumentation.httpx import HTTPXClientInstrumentor
from opentelemetry.sdk.resources import Resource
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import BatchSpanProcessor
from prometheus_client import CONTENT_TYPE_LATEST, CollectorRegistry, Counter, Histogram, generate_latest
from prometheus_client.process_collector import ProcessCollector
from prometheus_client.platform_collector import PlatformCollector
from pydantic import BaseModel

from ai_contract.apis.ai_api import router as contract_router
from ai_contract.apis.ai_api_base import BaseAiApi
from ai_contract.models.health import Health as ContractHealth

from delivery import prepare_for_delivery

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

# OpenTelemetry — feed spans to the cluster otel-collector so ai-service shows
# up in Jaeger alongside the Java services. The collector address comes from
# OTEL_EXPORTER_OTLP_ENDPOINT (banking-ai-config ConfigMap, http/protobuf on
# :4318 — same as the frontend Node service uses).
_otel_endpoint = os.environ.get("OTEL_EXPORTER_OTLP_ENDPOINT", "http://otel-collector.observability:4318")
_otel_service_name = os.environ.get("OTEL_SERVICE_NAME", "ai-service")
_otel_resource_attributes = {
    key.strip(): value.strip()
    for item in os.environ.get("OTEL_RESOURCE_ATTRIBUTES", "service.namespace=banking-app").split(",")
    if "=" in item
    for key, value in [item.split("=", 1)]
}
_otel_resource_attributes["service.name"] = _otel_service_name
_tracer_provider = TracerProvider(resource=Resource.create(_otel_resource_attributes))
_tracer_provider.add_span_processor(BatchSpanProcessor(OTLPSpanExporter(endpoint=f"{_otel_endpoint.rstrip('/')}/v1/traces")))
trace.set_tracer_provider(_tracer_provider)
HTTPXClientInstrumentor().instrument()

app = FastAPI(title="AI Service")
FastAPIInstrumentor.instrument_app(app)
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)
app.include_router(contract_router)

# Match the Spring Boot Micrometer metric name + label set the banking-app dashboards use,
# so /api/chat shows up in the cross-service "Errors by Endpoint" / "Status Codes by Service"
# panels alongside the Java services. Histogram (not Counter) so the exposed series is named
# http_server_requests_seconds_count — without prometheus_client's automatic `_total` suffix.
_metrics_registry = CollectorRegistry()
# Standard process / runtime metrics so dashboards that show CPU + memory across
# every service (not just JVM ones) have data for ai-service. Emits
# process_cpu_seconds_total, process_resident_memory_bytes, process_open_fds,
# python_info, etc.
ProcessCollector(registry=_metrics_registry)
PlatformCollector(registry=_metrics_registry)
_HTTP_REQUESTS = Histogram(
    "http_server_requests_seconds",
    "HTTP server requests (Micrometer-compatible)",
    ["method", "uri", "status", "outcome"],
    registry=_metrics_registry,
)

# Outbound calls to the 5 LLM providers — feeds the Errors-dashboard "AI Provider Calls" panel
# the same way fraud_external_requests_total used to feed the old fraud third-party panel.
_PROVIDER_REQUESTS = Counter(
    "ai_provider_requests_total",
    "Outbound calls to LLM providers from ai-service",
    ["provider", "status"],
    registry=_metrics_registry,
)


@app.middleware("http")
async def _record_http_metrics(request: Request, call_next):
    if request.url.path in ("/metrics", "/actuator/prometheus", "/health"):
        return await call_next(request)
    start = time.monotonic()
    status_code = 500
    try:
        response = await call_next(request)
        status_code = response.status_code
        return response
    finally:
        elapsed = time.monotonic() - start
        status = str(status_code)
        outcome = "SUCCESS" if status_code < 400 else "CLIENT_ERROR" if status_code < 500 else "SERVER_ERROR"
        _HTTP_REQUESTS.labels(request.method, request.url.path, status, outcome).observe(elapsed)


@app.get("/actuator/prometheus")
@app.get("/metrics")
def _metrics_endpoint() -> Response:
    return Response(generate_latest(_metrics_registry), media_type=CONTENT_TYPE_LATEST)

SYSTEM_PROMPT = (
    "You are a helpful banking assistant for Apex Banking. "
    "Answer questions about the user's accounts and transactions. "
    "Be concise and professional."
)

PROVIDERS = [
    {"provider": "anthropic", "name": "Anthropic Claude", "model": "claude-sonnet-4-20250514"},
    {"provider": "openai", "name": "OpenAI GPT-4o Mini", "model": "gpt-4o-mini"},
    {"provider": "gemini", "name": "Google Gemini", "model": "gemini-2.0-flash"},
    {"provider": "xai", "name": "xAI Grok", "model": "grok-3-mini"},
    {"provider": "openrouter", "name": "OpenRouter Mistral", "model": "mistralai/mistral-small-3.2-24b-instruct"},
]

# Provider endpoints — overridable via env for local development and staging.
PROVIDER_URLS = {
    "anthropic": os.environ.get("ANTHROPIC_URL", "https://api.anthropic.com/v1/messages"),
    "openai": os.environ.get("OPENAI_URL", "https://api.openai.com/v1/chat/completions"),
    "gemini_base": os.environ.get("GEMINI_URL", "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent"),
    "xai": os.environ.get("XAI_URL", "https://api.x.ai/v1/chat/completions"),
    "openrouter": os.environ.get("OPENROUTER_URL", "https://openrouter.ai/api/v1/chat/completions"),
}

class ChatRequest(BaseModel):
    message: str
    accountContext: str = ""
    locale: str = "en-US"


class ProviderResult(BaseModel):
    provider: str
    name: str
    model: str
    message: str = ""
    error: str | None = None
    durationMs: int = 0


class ChatResponse(BaseModel):
    results: list[ProviderResult]


async def call_anthropic(client: httpx.AsyncClient, message: str, context: str) -> ProviderResult:
    info = PROVIDERS[0]
    start = time.monotonic()
    try:
        user_content = f"{context}\n\n{message}" if context else message
        resp = await client.post(
            PROVIDER_URLS["anthropic"],
            headers={
                "x-api-key": os.environ.get("AI_API_KEY", ""),
                "anthropic-version": "2023-06-01",
                "content-type": "application/json",
            },
            json={
                "model": info["model"],
                "max_tokens": 1024,
                "system": SYSTEM_PROMPT,
                "messages": [{"role": "user", "content": user_content}],
            },
        )
        _PROVIDER_REQUESTS.labels(info["provider"], str(resp.status_code)).inc()
        resp.raise_for_status()
        data = resp.json()
        text = data["content"][0]["text"]
        return ProviderResult(**info, message=text, durationMs=_elapsed(start))
    except Exception as e:
        return ProviderResult(**info, error=str(e), durationMs=_elapsed(start))


async def call_openai(client: httpx.AsyncClient, message: str, context: str) -> ProviderResult:
    info = PROVIDERS[1]
    start = time.monotonic()
    try:
        resp = await client.post(
            PROVIDER_URLS["openai"],
            headers={
                "Authorization": f"Bearer {os.environ.get('OPENAI_API_KEY', '')}",
                "Content-Type": "application/json",
            },
            json={
                "model": info["model"],
                "max_tokens": 1024,
                "messages": _openai_messages(message, context),
            },
        )
        _PROVIDER_REQUESTS.labels(info["provider"], str(resp.status_code)).inc()
        resp.raise_for_status()
        data = resp.json()
        text = data["choices"][0]["message"]["content"]
        return ProviderResult(**info, message=text, durationMs=_elapsed(start))
    except Exception as e:
        return ProviderResult(**info, error=str(e), durationMs=_elapsed(start))


async def call_gemini(client: httpx.AsyncClient, message: str, context: str) -> ProviderResult:
    info = PROVIDERS[2]
    start = time.monotonic()
    try:
        key = os.environ.get("GEMINI_API_KEY", "")
        user_content = f"{context}\n\n{message}" if context else message
        resp = await client.post(
            f"{PROVIDER_URLS['gemini_base']}?key={key}",
            headers={"Content-Type": "application/json"},
            json={
                "system_instruction": {"parts": [{"text": SYSTEM_PROMPT}]},
                "contents": [{"role": "user", "parts": [{"text": user_content}]}],
            },
        )
        _PROVIDER_REQUESTS.labels(info["provider"], str(resp.status_code)).inc()
        resp.raise_for_status()
        data = resp.json()
        text = data["candidates"][0]["content"]["parts"][0]["text"]
        return ProviderResult(**info, message=text, durationMs=_elapsed(start))
    except Exception as e:
        return ProviderResult(**info, error=str(e), durationMs=_elapsed(start))


async def call_xai(client: httpx.AsyncClient, message: str, context: str) -> ProviderResult:
    info = PROVIDERS[3]
    start = time.monotonic()
    try:
        resp = await client.post(
            PROVIDER_URLS["xai"],
            headers={
                "Authorization": f"Bearer {os.environ.get('XAI_API_KEY', '')}",
                "Content-Type": "application/json",
            },
            json={
                "model": info["model"],
                "max_tokens": 1024,
                "messages": _openai_messages(message, context),
            },
        )
        _PROVIDER_REQUESTS.labels(info["provider"], str(resp.status_code)).inc()
        resp.raise_for_status()
        data = resp.json()
        text = data["choices"][0]["message"]["content"]
        return ProviderResult(**info, message=text, durationMs=_elapsed(start))
    except Exception as e:
        return ProviderResult(**info, error=str(e), durationMs=_elapsed(start))


async def call_openrouter(client: httpx.AsyncClient, message: str, context: str) -> ProviderResult:
    info = PROVIDERS[4]
    start = time.monotonic()
    try:
        resp = await client.post(
            PROVIDER_URLS["openrouter"],
            headers={
                "Authorization": f"Bearer {os.environ.get('OPENROUTER_API_KEY', '')}",
                "Content-Type": "application/json",
            },
            json={
                "model": info["model"],
                "max_tokens": 1024,
                "messages": _openai_messages(message, context),
            },
        )
        _PROVIDER_REQUESTS.labels(info["provider"], str(resp.status_code)).inc()
        resp.raise_for_status()
        data = resp.json()
        text = data["choices"][0]["message"]["content"]
        return ProviderResult(**info, message=text, durationMs=_elapsed(start))
    except Exception as e:
        return ProviderResult(**info, error=str(e), durationMs=_elapsed(start))


def _openai_messages(message: str, context: str) -> list[dict]:
    msgs: list[dict] = [{"role": "system", "content": SYSTEM_PROMPT}]
    user_content = f"{context}\n\n{message}" if context else message
    msgs.append({"role": "user", "content": user_content})
    return msgs


def _elapsed(start: float) -> int:
    return int((time.monotonic() - start) * 1000)


async def chat(req: ChatRequest):
    if os.environ.get("AI_PROVIDER_MODE", "live") == "fixture":
        results = [
            ProviderResult(**info, message=f"{info['name']} fixture reply", durationMs=1)
            for info in PROVIDERS
        ]
        for result in results:
            prepare_for_delivery(result.message, req.locale)
        return ChatResponse(results=results)
    # Pass the user's locale through to the model so replies match their language/conventions.
    context = req.accountContext
    if req.locale:
        context = (f"{context}\n" if context else "") + (
            f"User locale: {req.locale}. Respond in the language and conventions for this locale."
        )
    async with httpx.AsyncClient(timeout=30.0) as client:
        results = await asyncio.gather(
            call_anthropic(client, req.message, context),
            call_openai(client, req.message, context),
            call_gemini(client, req.message, context),
            call_xai(client, req.message, context),
            call_openrouter(client, req.message, context),
        )
    for r in results:
        if r.error:
            logger.warning("provider=%s error=%s duration=%dms", r.provider, r.error, r.durationMs)
        else:
            logger.info("provider=%s duration=%dms", r.provider, r.durationMs)
    for r in results:
        if r.message:
            prepare_for_delivery(r.message, req.locale)
    return ChatResponse(results=list(results))


async def providers():
    return PROVIDERS


class AgentChatRequest(BaseModel):
    message: str
    accountId: str | None = None
    locale: str = "en-US"
    conversationClaim: str | None = None
    authToken: str = ""
    wait: bool = True


class DisputeResearchRequest(BaseModel):
    caseId: int
    locale: str = "en-US"
    wait: bool = True


async def agent_chat(req: AgentChatRequest, authorization: str = ""):
    """Start a durable banking agent workflow (tool-calling + guardrails)."""
    from temporal.client import start_banking_agent
    from temporal.models import AgentInput

    auth = req.authToken or authorization.removeprefix("Bearer ").strip()
    try:
        return await start_banking_agent(
            AgentInput(
                message=req.message,
                auth_token=auth,
                account_id=req.accountId,
                locale=req.locale,
                conversation_claim=req.conversationClaim,
            ),
            wait=req.wait,
        )
    except Exception as exc:
        logger.exception("agent chat failed")
        return Response(content=f"Agent workflow unavailable: {exc}", status_code=503)


async def dispute_research(req: DisputeResearchRequest, internal_token: str = ""):
    """Start multi-agent dispute research; persists draft via disputes internal API.

    Protected by the shared DISPUTES_INTERNAL_TOKEN (service-to-service only).
    Production dispute research is normally started by the disputes Temporal worker.
    """
    from temporal.client import start_dispute_research
    from temporal.models import DisputeResearchInput

    expected = os.environ.get("DISPUTES_INTERNAL_TOKEN", "")
    provided = internal_token
    if not expected or provided != expected:
        return Response(content="Internal token required", status_code=401)

    try:
        return await start_dispute_research(
            DisputeResearchInput(case_id=req.caseId, locale=req.locale),
            wait=req.wait,
        )
    except Exception as exc:
        logger.exception("dispute research failed")
        return Response(content=f"Dispute research unavailable: {exc}", status_code=503)


async def health():
    return {"status": "ok", "temporalQueue": "ai-banking"}


class ContractAi(BaseAiApi):
    async def chat(self, chat_request):
        return await chat(
            ChatRequest(
                message=chat_request.message,
                accountContext=chat_request.account_context or "",
                locale=chat_request.locale or "en-US",
            )
        )

    async def list_providers(self):
        return await providers()

    async def agent_chat(self, agent_chat_request, authorization):
        return await agent_chat(
            AgentChatRequest(
                message=agent_chat_request.message,
                accountId=agent_chat_request.account_id,
                locale=agent_chat_request.locale or "en-US",
                conversationClaim=agent_chat_request.conversation_claim,
                authToken=agent_chat_request.auth_token or "",
                wait=True if agent_chat_request.wait is None else agent_chat_request.wait,
            ),
            authorization or "",
        )

    async def dispute_research(self, dispute_research_request, x_internal_token):
        return await dispute_research(
            DisputeResearchRequest(
                caseId=dispute_research_request.case_id,
                locale=dispute_research_request.locale or "en-US",
                wait=True if dispute_research_request.wait is None else dispute_research_request.wait,
            ),
            x_internal_token or "",
        )

    async def ai_health(self):
        body = await health()
        return ContractHealth(status=body["status"], temporalQueue=body["temporalQueue"])
