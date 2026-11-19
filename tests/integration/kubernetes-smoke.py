"""Verify the frontend proxy and one end-to-end W3C trace on a test cluster."""

import json
import secrets
import sys
import time
import urllib.error
import urllib.request


def get_json(base, path, expected=200, body=None, headers=None):
    request = urllib.request.Request(
        base + path,
        data=json.dumps(body).encode() if body is not None else None,
        headers={"Content-Type": "application/json", **(headers or {})},
    )
    try:
        with urllib.request.urlopen(request, timeout=5) as response:
            if response.status != expected:
                raise RuntimeError(f"{path} returned HTTP {response.status}, expected {expected}")
            return json.load(response)
    except urllib.error.HTTPError as error:
        raise RuntimeError(f"{path} returned HTTP {error.code}, expected {expected}") from None


def main(frontend, jaeger, notifications):
    health = get_json(frontend, "/api/healthz")
    assert health["status"] == "UP" and health["service"] == "frontend"

    user = "smoke" + secrets.token_hex(8)
    password = "T3st!" + secrets.token_hex(16)
    registration = get_json(frontend, "/api/users/register", 201, {
        "username": user, "email": user + "@example.test", "password": password,
    })
    assert registration["username"] == user and registration["id"] > 0

    login = get_json(frontend, "/api/users/login", body={"usernameOrEmail": user, "password": password})
    assert login["id"] == registration["id"]
    trace_id, parent_id = secrets.token_hex(16), secrets.token_hex(8)
    accounts = get_json(frontend, "/api/accounts", headers={
        "Authorization": "Bearer " + login["token"],
        "traceparent": f"00-{trace_id}-{parent_id}-01",
    })
    assert accounts == [], "new user must have an empty account list"
    print("Kubernetes smoke passed for frontend health, registration, login and account listing")

    expected_services = {"frontend", "api-gateway", "accounts-service"}
    observed = set()
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline:
        try:
            payload = get_json(jaeger, "/api/traces/" + trace_id)
        except (RuntimeError, OSError):
            payload = {"data": []}
        for record in payload["data"]:
            services = {
                record["processes"][span["processID"]]["serviceName"]
                for span in record["spans"]
                if span["traceID"] == trace_id
            }
            observed = services
            if expected_services <= services:
                print(f"Trace {trace_id} contained spans from all three services")
                verify_notification(frontend, notifications, login)
                return
        time.sleep(2)
    raise SystemExit(f"Trace {trace_id} missing spans from {', '.join(sorted(expected_services - observed))}")


def verify_notification(frontend, notifications, login):
    auth = {"Authorization": "Bearer " + login["token"]}
    account = get_json(frontend, "/api/accounts", 201, {
        "accountType": "CHECKING", "initialBalance": 0,
    }, auth)
    transaction = get_json(frontend, "/api/transactions/deposit", 201, {
        "accountId": account["id"], "amount": 10, "description": "Kubernetes smoke deposit",
    }, auth)
    deadline = time.monotonic() + 45
    while time.monotonic() < deadline:
        events = get_json(notifications, f"/notifications/{login['id']}") or []
        if any(event["transaction_id"] == transaction["id"] and event["status"] == "COMPLETED"
               for event in events):
            print("The deposit notification reached the consumer through Redpanda")
            return
        time.sleep(2)
    raise SystemExit("The deposit notification did not reach the consumer")


if __name__ == "__main__":
    main(*sys.argv[1:])
