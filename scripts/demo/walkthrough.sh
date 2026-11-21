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
  "${base}/api/v1/users/register" >/dev/null

echo "Sign in"
curl -fsS -c "$jar" -b "$jar" -H 'content-type: application/json' -H 'X-Banking-Session: cookie' \
  -d "{\"usernameOrEmail\":\"${username}\",\"password\":\"${password}\"}" \
  "${base}/api/v1/users/login" >/dev/null

create_account() {
  curl -fsS -c "$jar" -b "$jar" -H 'content-type: application/json' \
    -d "{\"accountType\":\"$1\",\"initialBalance\":$2}" \
    "${base}/api/v1/accounts"
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
  -d "$body" "${base}/api/v1/transactions/transfer")"
second="$(curl -fsS -c "$jar" -b "$jar" -H 'content-type: application/json' -H "Idempotency-Key: ${key}" \
  -d "$body" "${base}/api/v1/transactions/transfer")"
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
  "${base}/api/v1/disputes" >/dev/null
curl -fsS -c "$jar" -b "$jar" -H 'content-type: application/json' -H "Idempotency-Key: statement-${suffix}" \
  -d '{}' "${base}/api/v1/accounts/${from}/statement-jobs" >/dev/null

echo "Apply for KYC and wait until the worker approves it"
curl -fsS -c "$jar" -b "$jar" -H 'content-type: application/json' \
  -d '{"fullName":"Demo Customer","documentReference":"passport"}' \
  "${base}/api/v1/kyc" >/dev/null
python3 - "$base" "$jar" <<'PY'
import json, sys, time, urllib.request
base, jar = sys.argv[1:]
cookie = open(jar).read().splitlines()[-1].split("\t")[-1]
for _ in range(30):
    request = urllib.request.Request(base + "/api/v1/kyc", headers={"Cookie": "auth_token=" + cookie})
    status = json.load(urllib.request.urlopen(request)).get("status")
    if status == "APPROVED":
        print("kyc approved")
        break
    time.sleep(1)
else:
    raise SystemExit("kyc did not approve")
PY

echo "Issue a card and authorize a purchase"
card="$(curl -fsS -c "$jar" -b "$jar" -H 'content-type: application/json' \
  -d "{\"accountId\":${from}}" "${base}/api/v1/cards")"
token="$(printf '%s' "$card" | python3 -c 'import json,sys; print(json.load(sys.stdin)["token"])')"
curl -fsS -c "$jar" -b "$jar" -H 'content-type: application/json' \
  -d "{\"cardToken\":\"${token}\",\"idempotencyKey\":\"card-${suffix}\",\"amount\":\"5.00\",\"merchant\":\"Cafe\"}" \
  "${base}/api/v1/cards/authorize" >/dev/null

echo "Ask the fixture AI assistant"
curl -fsS -c "$jar" -b "$jar" -H 'content-type: application/json' \
  -d '{"message":"What can you see?","locale":"en-US"}' \
  "${base}/api/ai/chat" >/dev/null

echo
echo "Open the trace in Jaeger: http://localhost:16686"
echo "Outbox age is on the Banking App dashboard: http://localhost:3001 (admin / admin)"
echo "Notifications are on the transaction-events topic. Payments stay on the Compose overlay."
