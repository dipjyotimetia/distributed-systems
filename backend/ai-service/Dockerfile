FROM python:3.15.2-slim
COPY --from=ghcr.io/astral-sh/uv:0.13.9 /uv /uvx /bin/

WORKDIR /app
ENV UV_COMPILE_BYTECODE=1 \
    UV_LINK_MODE=copy \
    UV_PYTHON_DOWNLOADS=0 \
    PATH="/app/.venv/bin:$PATH"

# Fixture mode (Compose/CI default) only needs core deps. Live LLM calls can
# sync the optional `live` extra in overlays that set AI_INSTALL_LIVE_DEPS=1.
ARG AI_INSTALL_LIVE_DEPS=0

RUN apt-get update \
    && apt-get install -y --no-install-recommends libstdc++6 \
    && rm -rf /var/lib/apt/lists/*

COPY pyproject.toml uv.lock ./
# grpcio 1.85 publishes a Python 3.15 wheel. Prefer wheels so image builds stay
# under the temporal integration timeout.
RUN --mount=type=cache,target=/root/.cache/uv \
    if [ "$AI_INSTALL_LIVE_DEPS" = "1" ]; then \
      uv sync --frozen --no-dev --extra live; \
    else \
      uv sync --frozen --no-dev; \
    fi

COPY . .
EXPOSE 8080
# AI_MODE=worker runs the Temporal worker; default serves the FastAPI chat/agent API.
CMD ["sh", "-c", "if [ \"$AI_MODE\" = worker ]; then python -m temporal.worker; else uvicorn main:app --host 0.0.0.0 --port 8080; fi"]
