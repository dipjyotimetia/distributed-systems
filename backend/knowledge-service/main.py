"""Knowledge service — document vault + retrieval for RAG / MCP."""

from __future__ import annotations

import logging
import os
import re
import time
import uuid
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Any
from urllib.parse import quote

import retrieve
import seed
import storage
import store
from auth import UserOrInternal
from fastapi import (
    FastAPI,
    File,
    Form,
    HTTPException,
    Query,
    Request,
    Response,
    UploadFile,
)
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import Response as FastAPIResponse
from opentelemetry import trace
from opentelemetry.exporter.otlp.proto.http.trace_exporter import OTLPSpanExporter
from opentelemetry.instrumentation.fastapi import FastAPIInstrumentor
from opentelemetry.sdk.resources import Resource
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import BatchSpanProcessor
from prometheus_client import (
    CONTENT_TYPE_LATEST,
    CollectorRegistry,
    Histogram,
    generate_latest,
)
from prometheus_client.platform_collector import PlatformCollector
from prometheus_client.process_collector import ProcessCollector

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

_otel_endpoint = os.environ.get(
    "OTEL_EXPORTER_OTLP_ENDPOINT", "http://otel-collector:4318"
)
_otel_service_name = os.environ.get("OTEL_SERVICE_NAME", "knowledge-service")
_otel_resource_attributes = {
    key.strip(): value.strip()
    for item in os.environ.get(
        "OTEL_RESOURCE_ATTRIBUTES", "service.namespace=banking-app"
    ).split(",")
    if "=" in item
    for key, value in [item.split("=", 1)]
}
_otel_resource_attributes["service.name"] = _otel_service_name
_tracer_provider = TracerProvider(resource=Resource.create(_otel_resource_attributes))
_tracer_provider.add_span_processor(
    BatchSpanProcessor(
        OTLPSpanExporter(endpoint=f"{_otel_endpoint.rstrip('/')}/v1/traces")
    )
)
trace.set_tracer_provider(_tracer_provider)

ALLOWED_DOC_TYPES = {"POLICY", "KYC_EVIDENCE", "DISPUTE_EVIDENCE", "STATEMENT", "OTHER"}
ALLOWED_VISIBILITY = {"OWNER", "BANK", "PUBLIC_BANK"}
MAX_UPLOAD_BYTES = int(
    os.environ.get("KNOWLEDGE_MAX_UPLOAD_BYTES", str(5 * 1024 * 1024))
)


@asynccontextmanager
async def lifespan(_app: FastAPI):
    storage.ensure_ready()
    store.migrate()
    created = seed.seed_policies()
    logger.info(
        "knowledge-service ready storage=%s docs=%s seeded=%s",
        storage.backend_name(),
        store.count_documents(),
        created,
    )
    yield


app = FastAPI(title="Knowledge Service", lifespan=lifespan)
FastAPIInstrumentor.instrument_app(app)
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)

_metrics_registry = CollectorRegistry()
ProcessCollector(registry=_metrics_registry)
PlatformCollector(registry=_metrics_registry)
_HTTP_REQUESTS = Histogram(
    "http_server_requests_seconds",
    "HTTP server requests (Micrometer-compatible)",
    ["method", "uri", "status", "outcome"],
    registry=_metrics_registry,
)


@app.middleware("http")
async def _record_http_metrics(request: Request, call_next):
    if request.url.path in ("/metrics", "/actuator/prometheus", "/api/v1/health"):
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
        outcome = (
            "SUCCESS"
            if status_code < 400
            else "CLIENT_ERROR"
            if status_code < 500
            else "SERVER_ERROR"
        )
        _HTTP_REQUESTS.labels(
            request.method, request.url.path, status, outcome
        ).observe(elapsed)


@app.get("/metrics")
@app.get("/actuator/prometheus")
def metrics() -> Response:
    return Response(generate_latest(_metrics_registry), media_type=CONTENT_TYPE_LATEST)


@app.get("/api/v1/health")
@app.get("/api/v1/knowledge/health")
def health() -> dict[str, Any]:
    return {
        "status": "UP",
        "service": "knowledge-service",
        "storage": storage.backend_name(),
        "documents": store.count_documents(),
    }


@app.get("/api/v1/knowledge/documents")
def list_documents(
    principal: UserOrInternal,
    docType: str | None = None,
    caseId: str | None = None,
    accountId: str | None = None,
    limit: int = Query(default=20, ge=1, le=100),
) -> dict[str, Any]:
    rows = store.list_documents(
        user_id=principal.user_id,
        internal=principal.internal,
        doc_type=docType,
        case_id=caseId,
        account_id=accountId,
        limit=limit,
    )
    return {"items": [store.to_api_document(row) for row in rows]}


@app.post("/api/v1/knowledge/documents", status_code=201)
async def upload_document(
    principal: UserOrInternal,
    file: UploadFile = File(...),
    title: str = Form(...),
    docType: str = Form(...),
    caseId: str | None = Form(default=None),
    accountId: str | None = Form(default=None),
    visibility: str = Form(default="OWNER"),
) -> dict[str, Any]:
    if docType not in ALLOWED_DOC_TYPES:
        raise HTTPException(status_code=400, detail="Invalid docType")
    if visibility not in ALLOWED_VISIBILITY:
        raise HTTPException(status_code=400, detail="Invalid visibility")
    if docType == "POLICY" and not principal.internal:
        raise HTTPException(
            status_code=403, detail="Policy uploads require internal token"
        )
    # Customer JWT callers may only create OWNER-scoped documents. BANK /
    # PUBLIC_BANK visibility is reserved for internal/service uploads so
    # dispute/KYC evidence cannot be broadcast to every authenticated user.
    if not principal.internal and visibility != "OWNER":
        raise HTTPException(
            status_code=403,
            detail="Customer uploads must use OWNER visibility",
        )
    if visibility == "OWNER" and principal.user_id is None and not principal.internal:
        raise HTTPException(status_code=401, detail="Owner uploads require JWT")

    data = await file.read()
    if not data:
        raise HTTPException(status_code=400, detail="Empty file")
    if len(data) > MAX_UPLOAD_BYTES:
        raise HTTPException(status_code=400, detail="File too large")

    safe_name = _safe_filename(file.filename or "upload.bin")
    content_type = file.content_type or "application/octet-stream"
    text_content = _extract_text(data, content_type, safe_name)
    doc_id = uuid.uuid4()
    key = f"docs/{doc_id}/{safe_name}"
    storage.put_bytes(key, data, content_type)
    owner = (
        None
        if visibility in ("BANK", "PUBLIC_BANK") and principal.internal
        else principal.user_id
    )
    if visibility == "OWNER":
        owner = principal.user_id
    try:
        row = store.insert_document(
            title=title.strip() or safe_name,
            doc_type=docType,
            visibility=visibility,
            content_type=content_type,
            size_bytes=len(data),
            storage_key=key,
            text_content=text_content,
            owner_user_id=owner,
            case_id=caseId,
            account_id=accountId,
        )
    except Exception:
        storage.delete_bytes(key)
        raise
    return store.to_api_document(row)


@app.get("/api/v1/knowledge/documents/{document_id}")
def get_document(document_id: uuid.UUID, principal: UserOrInternal) -> dict[str, Any]:
    row = _require_visible(document_id, principal)
    return store.to_api_document(row)


@app.get("/api/v1/knowledge/documents/{document_id}/content")
def download_document(
    document_id: uuid.UUID, principal: UserOrInternal
) -> FastAPIResponse:
    row = _require_visible(document_id, principal)
    try:
        data = storage.get_bytes(row["storage_key"])
    except FileNotFoundError as exc:
        raise HTTPException(status_code=404, detail="Blob missing") from exc
    return FastAPIResponse(
        content=data,
        media_type=row["content_type"],
        headers={"Content-Disposition": _content_disposition(row["title"])},
    )


@app.get("/api/v1/knowledge/documents/{document_id}/excerpt")
def document_excerpt(
    document_id: uuid.UUID,
    principal: UserOrInternal,
    maxChars: int = Query(default=1200, ge=64, le=8000),
) -> dict[str, Any]:
    row = _require_visible(document_id, principal)
    text = (row.get("text_content") or "")[:maxChars]
    return {
        "documentId": str(row["id"]),
        "title": row["title"],
        "excerpt": text,
        "contentType": row["content_type"],
        "citations": [row["title"]],
    }


@app.post("/api/v1/knowledge/search")
async def search_knowledge(
    principal: UserOrInternal, request: Request
) -> dict[str, Any]:
    body = await request.json()
    query = str(body.get("query") or "").strip()
    if not query:
        raise HTTPException(status_code=400, detail="query is required")
    limit = int(body.get("limit") or 5)
    limit = max(1, min(limit, 20))
    doc_type = body.get("docType")
    case_id = body.get("caseId")
    hits = retrieve.search(
        query=query,
        user_id=principal.user_id,
        internal=principal.internal,
        doc_type=doc_type,
        case_id=case_id,
        limit=limit,
    )
    return {"query": query, "hits": hits}


def _require_visible(
    document_id: uuid.UUID, principal: UserOrInternal
) -> dict[str, Any]:
    row = store.get_document(document_id)
    if not row:
        raise HTTPException(status_code=404, detail="Not found")
    if principal.internal:
        return row
    if row["visibility"] in ("BANK", "PUBLIC_BANK"):
        return row
    if principal.user_id is not None and row["owner_user_id"] == principal.user_id:
        return row
    raise HTTPException(status_code=404, detail="Not found")


def _safe_filename(name: str) -> str:
    """Basename only — reject path traversal and empty names."""
    base = Path(name or "").name.strip()
    if not base or base in {".", ".."}:
        return "upload.bin"
    # Keep a conservative character set for object keys and download names.
    cleaned = re.sub(r"[^A-Za-z0-9._-]+", "_", base).strip("._")
    return cleaned or "upload.bin"


def _content_disposition(title: str) -> str:
    """RFC 5987 Content-Disposition that cannot inject headers via quotes/newlines."""
    ascii_name = _safe_filename(title)
    if not ascii_name.lower().endswith(
        (".bin", ".md", ".txt", ".pdf", ".json", ".csv")
    ):
        ascii_name = f"{ascii_name}.bin"
    utf8_name = re.sub(r"[\r\n\"]+", " ", (title or ascii_name)).strip() or ascii_name
    return f"attachment; filename=\"{ascii_name}\"; filename*=UTF-8''{quote(utf8_name)}"


def _extract_text(data: bytes, content_type: str, filename: str) -> str:
    lowered = (content_type or "").lower()
    name = (filename or "").lower()
    if "text/" in lowered or name.endswith((".md", ".txt", ".csv", ".json")):
        return data.decode("utf-8", errors="replace")
    # Binary uploads still get a searchable stub for fixture RAG demos.
    return f"Binary document {filename} ({content_type}) size={len(data)}"
