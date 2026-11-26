# ai-service

Multi-provider chat, Temporal banking agent, and dispute research.

Python deps are managed with **uv** only (`pyproject.toml` + `uv.lock`).

## Local

```bash
cd backend/ai-service
uv sync
AI_PROVIDER_MODE=fixture uv run uvicorn main:app --port 8084
```

Optional live LLM extras:

```bash
uv sync --extra live
```

## Tests

```bash
uv sync
AI_PROVIDER_MODE=fixture uv run pytest
```

## Worker

```bash
AI_MODE=worker AI_PROVIDER_MODE=fixture uv run python -m temporal.worker
```
