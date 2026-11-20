#!/bin/sh
# Golden-path demo against the default Compose frontend.
# Requires: docker compose stack on http://localhost:3000, python3, curl.
set -eu

base="${DEMO_BASE_URL:-http://localhost:3000}"
jar="$(mktemp)"
trap 'rm -f "$jar"' EXIT

suffix="$(date +%s)"
username="demo${suffix}"
password="DemoPass!${suffix}"
email="${username}@example.test"

echo "Register ${username}"
curl -fsS -c "$jar" -b "$jar" -H 'content-type: application/json' \
  -d "{\"username\":\"${username}\",\"email\":\"${email}\",\"password\":\"${password}\"}" \
  "${base}/api/users/register" >/dev/null

echo "Sign in"
curl -fsS -c "$jar" -b "$jar" -H 'content-type: application/json' -H 'X-Banking-Session: cookie' \
  -d "{\"usernameOrEmail\":\"${username}\",\"password\":\"${password}\"}" \
  "${base}/api/users/login" >/dev/null

create_account() {
  curl -fsS -c "$jar" -b "$jar" -H 'content-type: application/json' \
    -d "{\"accountType\":\"$1\",\"initialBalance\":$2}" \
    "${base}/api/accounts"
}

echo "Open two accounts"
checking="$(create_account CHECKING 100)"
savings="$(create_account SAVINGS 20)"
from="$(printf '%s' "$checking" | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')"
to="$(printf '%s' "$savings" | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')"

echo "Transfer 10 with a stable idempotency key, then retry it"
key="demo-transfer-${suffix}"
body="{\"fromAccountId\":${from},\"toAccountId\":${to},\"amount\":10,\"description\":\"Demo transfer\"}"
first="$(curl -fsS -c "$jar" -b "$jar" -H 'content-type: application/json' -H "Idempotency-Key: ${key}" \
  -d "$body" "${base}/api/transactions/transfer")"
second="$(curl -fsS -c "$jar" -b "$jar" -H 'content-type: application/json' -H "Idempotency-Key: ${key}" \
  -d "$body" "${base}/api/transactions/transfer")"
python3 - "$first" "$second" <<'PY'
import json, sys
first, second = (json.loads(item) for item in sys.argv[1:])
if first["id"] != second["id"]:
    raise SystemExit(f"retry created a second transfer: {first['id']} then {second['id']}")
print(f"transfer {first['id']} balance path is stable")
PY
transfer_id="$(printf '%s' "$first" | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')"

echo "File a dispute and queue a statement"
curl -fsS -c "$jar" -b "$jar" -H 'content-type: application/json' \
  -d "{\"transferId\":${transfer_id},\"reason\":\"Demo dispute\"}" \
  "${base}/api/disputes" >/dev/null
curl -fsS -c "$jar" -b "$jar" -H 'content-type: application/json' -H "Idempotency-Key: statement-${suffix}" \
  -d '{}' "${base}/api/accounts/${from}/statement-jobs" >/dev/null

echo "Ask the fixture AI assistant"
curl -fsS -c "$jar" -b "$jar" -H 'content-type: application/json' \
  -d '{"message":"What can you see?","locale":"en-US"}' \
  "${base}/api/ai/chat" >/dev/null

echo
echo "Open the trace in Jaeger: http://localhost:16686"
echo "Outbox age is on the Banking App dashboard: http://localhost:3001 (admin / admin)"
echo "Notifications are on the transaction-events topic. Payments stay on the Compose overlay."
