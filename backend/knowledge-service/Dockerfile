FROM python:3.15.2-slim
COPY --from=ghcr.io/astral-sh/uv:0.11.9 /uv /uvx /bin/

WORKDIR /app
ENV UV_COMPILE_BYTECODE=1 \
    UV_LINK_MODE=copy \
    UV_PYTHON_DOWNLOADS=0 \
    PATH="/app/.venv/bin:$PATH"

RUN apt-get update \
    && apt-get install -y --no-install-recommends libstdc++6 \
    && rm -rf /var/lib/apt/lists/*

COPY pyproject.toml uv.lock ./
RUN --mount=type=cache,target=/root/.cache/uv \
    uv sync --frozen --no-dev

COPY . .
EXPOSE 8080
# KNOWLEDGE_MODE=mcp runs the thin MCP stdio server; default serves FastAPI.
CMD ["sh", "-c", "if [ \"$KNOWLEDGE_MODE\" = mcp ]; then python -m mcp_server; else uvicorn main:app --host 0.0.0.0 --port 8080; fi"]
# MCP mode uses the official modelcontextprotocol/python-sdk (mcp.server.MCPServer).
