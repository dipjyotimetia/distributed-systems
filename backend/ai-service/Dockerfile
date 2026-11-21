FROM python:3.15.2-slim
WORKDIR /app
COPY requirements.txt .
# Fixture mode (Compose/CI default) only needs core deps. Live LLM calls can
# install requirements-live.txt in overlays that set AI_PROVIDER_MODE=live.
ARG AI_INSTALL_LIVE_DEPS=0
COPY requirements-live.txt .
RUN pip install --no-cache-dir -r requirements.txt \
    && if [ "$AI_INSTALL_LIVE_DEPS" = "1" ]; then pip install --no-cache-dir -r requirements-live.txt; fi
COPY . .
EXPOSE 8080
# AI_MODE=worker runs the Temporal worker; default serves the FastAPI chat/agent API.
CMD ["sh", "-c", "if [ \"$AI_MODE\" = worker ]; then python -m temporal.worker; else uvicorn main:app --host 0.0.0.0 --port 8080; fi"]
