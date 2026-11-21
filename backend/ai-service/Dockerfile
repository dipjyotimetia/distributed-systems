FROM python:3.15.2-slim
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY . .
EXPOSE 8080
# AI_MODE=worker runs the Temporal worker; default serves the FastAPI chat/agent API.
CMD ["sh", "-c", "if [ \"$AI_MODE\" = worker ]; then python -m temporal.worker; else uvicorn main:app --host 0.0.0.0 --port 8080; fi"]
