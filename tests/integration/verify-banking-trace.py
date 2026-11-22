#!/usr/bin/env python3
"""Verify a connected banking trace exists in Jaeger after a login/transfer flow.

Usage:
  JAEGER_URL=http://localhost:16686 TRACE_ID=<id> python3 tests/integration/verify-banking-trace.py

If TRACE_ID is unset, looks up recent traces for service=frontend (or frontend-browser)
and asserts the expected services appear in the same trace.
"""

from __future__ import annotations

import json
import os
import sys
import urllib.parse
import urllib.request

JAEGER = os.environ.get("JAEGER_URL", "http://localhost:16686").rstrip("/")
EXPECTED = {
    s.strip()
    for s in os.environ.get(
        "EXPECTED_SERVICES",
        "frontend,api-gateway,user-service,accounts-service,transactions-service,fraud-service",
    ).split(",")
    if s.strip()
}


def get(path: str):
    with urllib.request.urlopen(f"{JAEGER}{path}", timeout=15) as resp:
        return json.load(resp)


def services_in_trace(trace: dict) -> set[str]:
    names = set()
    for span in trace.get("spans", []):
        process = trace.get("processes", {}).get(span.get("processID"), {})
        if process.get("serviceName"):
            names.add(process["serviceName"])
    return names


def main() -> int:
    trace_id = os.environ.get("TRACE_ID")
    if not trace_id:
        service = os.environ.get("LOOKUP_SERVICE", "frontend")
        query = urllib.parse.urlencode({"service": service, "limit": 20})
        payload = get(f"/api/traces?{query}")
        traces = payload.get("data") or []
        if not traces:
            # Browser SDK may be the root
            query = urllib.parse.urlencode({"service": "frontend-browser", "limit": 20})
            traces = get(f"/api/traces?{query}").get("data") or []
        if not traces:
            print("No recent traces found for frontend / frontend-browser", file=sys.stderr)
            return 1
        best = max(traces, key=lambda t: len(services_in_trace(t)))
        trace_id = best.get("traceID")
        found = services_in_trace(best)
    else:
        payload = get(f"/api/traces/{trace_id}")
        traces = payload.get("data") or []
        if not traces:
            print(f"Trace {trace_id} not found", file=sys.stderr)
            return 1
        found = services_in_trace(traces[0])

    missing = EXPECTED - found
    print(f"trace_id={trace_id}")
    print(f"services={sorted(found)}")
    if missing:
        print(f"missing_expected={sorted(missing)}", file=sys.stderr)
        return 1
    print("ok")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
