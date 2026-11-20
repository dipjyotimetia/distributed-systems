"""Verify that one browser payment appears across the application boundaries."""

import json
import re
import sys
import time
import urllib.error
import urllib.request


def observe(trace, payment_id):
    operations = set()
    for record in trace.get("data", []):
        for span in record["spans"]:
            attributes = {tag["key"]: tag["value"] for tag in span["tags"]}
            if attributes.get("payment.id") != payment_id:
                continue
            service = record["processes"][span["processID"]]["serviceName"]
            operation = span["operationName"]
            if service == "payments-service" and operation.startswith("POST "):
                operations.add("payment.http")
            else:
                operations.add(operation)
    return operations


def main(jaeger, evidence_path):
    with open(evidence_path, encoding="utf-8") as stream:
        evidence = json.load(stream)
    trace_id = evidence["traceId"]
    assert re.fullmatch(r"[0-9a-f]{32}", trace_id)
    expected = {
        "payment.http", "payment.temporal.advance", "payment.mq.publish",
        "clearing.mq.receive", "clearing.mq.reply", "payment.mq.reply", "payment.kafka.publish",
    }
    observed = set()
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline:
        try:
            with urllib.request.urlopen(f"{jaeger}/api/traces/{trace_id}", timeout=5) as response:
                observed = observe(json.load(response), evidence["paymentId"])
            if expected <= observed:
                print("Payment trace includes HTTP, Temporal, MQ and Kafka")
                return
        except (urllib.error.URLError, TimeoutError):
            pass
        time.sleep(2)
    raise AssertionError(f"Payment trace lacks operations: {', '.join(sorted(expected - observed))}")


if __name__ == "__main__":
    main(*sys.argv[1:])
