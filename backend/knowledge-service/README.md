# knowledge-service

Document vault and retrieval for banking evidence, KYC packs, and seeded policy
docs. This is the RAG / MCP foundation used by `ai-service` tools.

Python deps are managed with **uv** only (`pyproject.toml` + `uv.lock`).

## Run locally

```bash
cd backend/knowledge-service
uv sync
KNOWLEDGE_STORE=memory STORAGE_BACKEND=local LOCAL_BLOB_ROOT=/tmp/knowledge-blobs \
  uv run uvicorn main:app --port 8093
```

## Tests

```bash
uv sync
KNOWLEDGE_STORE=memory STORAGE_BACKEND=local uv run pytest
```

## MCP (stdio)

Uses the official [MCP Python SDK](https://github.com/modelcontextprotocol/python-sdk).
With the HTTP API already running:

```bash
KNOWLEDGE_SERVICE_URL=http://127.0.0.1:8093 \
  KNOWLEDGE_INTERNAL_TOKEN=local-knowledge-internal \
  uv run python -m mcp_server

# or via the SDK CLI
uv run mcp run mcp_server.py
uv run mcp dev mcp_server.py   # Inspector
```

Tools: `search_knowledge`, `get_document_excerpt`  
Resources: `knowledge://document/{document_id}`
