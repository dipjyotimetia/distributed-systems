"""Temporal worker entrypoint for ai-banking task queue."""

from __future__ import annotations

import asyncio
import logging
import os
from concurrent.futures import ThreadPoolExecutor
from http.server import BaseHTTPRequestHandler, HTTPServer
from threading import Thread

from temporalio.client import Client
from temporalio.worker import Worker

from temporal import TASK_QUEUE
from temporal.agent_workflow import BankingAgentWorkflow
from temporal.claim_check import claim_check_get, claim_check_put
from temporal.guardrails import guardrail_check
from temporal.llm import llm_complete
from temporal.research_workflow import DisputeResearchWorkflow
from temporal.tools import (
    execute_tool,
    get_balance,
    get_dispute_status,
    get_kyc_status,
    list_transactions,
    load_dispute_context,
    save_dispute_ai_draft,
)

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("ai-worker")

_client: Client | None = None


class _HealthHandler(BaseHTTPRequestHandler):
    def do_GET(self) -> None:  # noqa: N802
        if self.path != "/api/v1/health":
            self.send_response(404)
            self.end_headers()
            return
        ok = _client is not None
        self.send_response(200 if ok else 503)
        self.end_headers()
        self.wfile.write(b"ok" if ok else b"starting")

    def log_message(self, format: str, *args) -> None:  # noqa: A003
        return


def _serve_health() -> None:
    port = int(os.environ.get("HEALTH_PORT", "8080"))
    server = HTTPServer(("0.0.0.0", port), _HealthHandler)
    server.serve_forever()


async def run_worker() -> None:
    global _client
    address = os.environ.get("TEMPORAL_ADDRESS", "localhost:7233")
    namespace = os.environ.get("TEMPORAL_NAMESPACE", "default")
    logger.info("Connecting to Temporal at %s namespace=%s queue=%s", address, namespace, TASK_QUEUE)
    _client = await Client.connect(address, namespace=namespace)
    Thread(target=_serve_health, daemon=True).start()
    worker = Worker(
        _client,
        task_queue=TASK_QUEUE,
        workflows=[BankingAgentWorkflow, DisputeResearchWorkflow],
        activities=[
            llm_complete,
            guardrail_check,
            execute_tool,
            get_balance,
            list_transactions,
            get_kyc_status,
            get_dispute_status,
            load_dispute_context,
            save_dispute_ai_draft,
            claim_check_put,
            claim_check_get,
        ],
        activity_executor=ThreadPoolExecutor(max_workers=8),
    )
    logger.info("ai-worker listening on %s", TASK_QUEUE)
    await worker.run()


def main() -> None:
    asyncio.run(run_worker())


if __name__ == "__main__":
    main()
