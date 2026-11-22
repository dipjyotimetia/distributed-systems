#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/../../.."
project="payment-test-$(openssl rand -hex 6)"
evidence="$(mktemp /tmp/banking-payment-evidence.XXXXXX)"
export MICROSVC_PAYMENT_EVIDENCE="$evidence"
export MICROSVC_PAYMENTS=1
export BANKING_MQ_APP_PASSWORD="$(openssl rand -hex 24)"
export BANKING_CLEARING_DB_PASSWORD="$(openssl rand -hex 24)"
export BANKING_PAYMENTS_DB_PASSWORD="$(openssl rand -hex 24)"
export BANKING_PAYMENTS_JWT_SECRET="$(openssl rand -hex 32)"
export BANKING_PAYMENTS_MOVEMENT_SECRET="$(openssl rand -hex 32)"
export BANKING_PAYMENTS_COMPLIANCE_URL=http://provider-fixtures:8081/searches
export BANKING_PAYMENTS_COMPLIANCE_TOKEN=synthetic-token
export BANKING_SETTLEMENT_BUCKET=settlements-test
export BANKING_SETTLEMENT_S3_ENDPOINT=http://provider-fixtures:8081
export BANKING_SETTLEMENT_READER_KEY=synthetic-reader BANKING_SETTLEMENT_READER_SECRET=synthetic-reader
export BANKING_SETTLEMENT_WRITER_KEY=synthetic-writer BANKING_SETTLEMENT_WRITER_SECRET=synthetic-writer
export BANKING_PAYMENTS_REPLY_TIMEOUT=PT50S
export BANKING_CLEARING_SETTLEMENT_DELAY=PT20S

compose=(docker compose -p "$project" -f docker-compose.yml -f docker-compose.mq.yml -f docker-compose.payments.yml
  -f tests/integration/temporal/stack/compose.yml -f tests/integration/temporal/stack/payments.compose.yml)
cleanup() {
  local status=$?
  if [[ "$status" != 0 ]]; then
    "${compose[@]}" ps -a || true
    "${compose[@]}" logs --tail 40 frontend api-gateway user-service accounts-service transactions-service payments-service payments-worker clearing-simulator clearing-mq notification-service redpanda || true
  fi
  "${compose[@]}" down --volumes --remove-orphans || true
  rm -f "$evidence"
}
trap cleanup EXIT

build_services=(frontend api-gateway user-service accounts-service transactions-service fraud-service provider-fixtures
  payments-service payments-worker clearing-simulator payments-reconciliation notification-service ai-service)
if ! "${compose[@]}" build "${build_services[@]}"; then
  sleep 5
  "${compose[@]}" build "${build_services[@]}"
fi
# Pre-pull third-party images with retries; Docker Hub TLS timeouts are common in CI.
bash scripts/ci/docker-pull-retry.sh \
  temporalio/temporal:1.10.1 \
  postgres:19.0-alpine \
  apache/activemq-artemis:2.44.0-alpine \
  docker.redpanda.com/redpandadata/redpanda:v26.2.2 \
  mongo:9.0.8 \
  redis:8.10.3-alpine \
  jaegertracing/jaeger:2.23.0 \
  otel/opentelemetry-collector-contrib:0.165.0
infra_up() {
  local attempt=1
  while (( attempt <= 5 )); do
    if "${compose[@]}" up -d --wait payments-postgres clearing-postgres clearing-mq disputes-temporal redpanda; then
      return 0
    fi
    echo "infra compose up failed (attempt $attempt); retrying..." >&2
    "${compose[@]}" down --volumes --remove-orphans || true
    bash scripts/ci/docker-pull-retry.sh temporalio/temporal:1.10.1 || true
    sleep $((attempt * 10))
    attempt=$((attempt + 1))
  done
  return 1
}
infra_up
# Choose a future cutoff only after the infrastructure and images are available.
settlement_minutes="${BANKING_SETTLEMENT_WAIT_MINUTES:-3}"
read -r BANKING_SETTLEMENT_CUTOFF BANKING_SETTLEMENT_BUSINESS_DATE < <(SETTLEMENT_MINUTES="$settlement_minutes" python3 - <<'PY'
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo
import os
minutes = int(os.environ.get("SETTLEMENT_MINUTES", "3"))
end = datetime.now(ZoneInfo("Australia/Sydney")) + timedelta(minutes=minutes)
print(end.strftime("%H:%M"), end.date().isoformat())
PY
)
export BANKING_SETTLEMENT_CUTOFF BANKING_SETTLEMENT_BUSINESS_DATE
"${compose[@]}" up -d frontend payments-worker clearing-simulator notification-service jaeger otel-collector
export MICROSVC_FRONTEND="http://$("${compose[@]}" port frontend 3000)"
export MICROSVC_GATEWAY="http://$("${compose[@]}" port api-gateway 8080)"
export MICROSVC_NOTIFICATIONS="http://$("${compose[@]}" port notification-service 8080)"
fixtures="http://$("${compose[@]}" port provider-fixtures 8081)"

(cd frontend && npm run test:e2e:real-backend -- payments.real-backend.spec.ts --workers=1)
timeout 30 "${compose[@]}" exec -T redpanda rpk topic consume payment-events.v1 \
  -X brokers=redpanda:9092 -X registry.hosts=redpanda:8081 --use-schema-registry=value \
  -o :end --format '%k\t%v\n' |
  python3 tests/integration/verify-payment-event.py "$MICROSVC_PAYMENT_EVIDENCE"

jaeger="http://$("${compose[@]}" port jaeger 16686)"
python3 tests/integration/verify-payment-trace.py "$jaeger" "$MICROSVC_PAYMENT_EVIDENCE"

file_url="$fixtures/settlements-test/settlement/aud/$BANKING_SETTLEMENT_BUSINESS_DATE.dat"
for ((attempt = 0; attempt < 90; attempt++)); do
  if curl -fsS -o /dev/null "$file_url" 2>/dev/null; then break; fi
  sleep 2
done
curl -fsS "$file_url" | python3 tests/integration/verify-payment-settlement.py "$evidence"
"${compose[@]}" run --rm payments-reconciliation
"${compose[@]}" run --rm payments-reconciliation
payment_id="$(python3 -c 'import json,sys,uuid; print(uuid.UUID(json.load(open(sys.argv[1]))["paymentId"]))' "$evidence")"
movement="$("${compose[@]}" exec -T postgres psql -At -U postgres -d banking_app \
  -v ON_ERROR_STOP=1 -c "SELECT state || '|' || amount FROM accounts_service.movement_reservations WHERE operation_id = '$payment_id'")"
[[ "$movement" == 'COMMITTED|25.00' ]]
matched="$("${compose[@]}" exec -T payments-postgres psql -At -U payments -d payments \
  -v ON_ERROR_STOP=1 -c "SELECT count(*) FROM payments.reconciliation_result WHERE payment_id = '$payment_id' AND outcome = 'MATCHED'")"
[[ "$matched" == 1 ]]
echo "Settlement file and idempotent reconciliation verified"

MICROSVC_PAYMENT_FIXTURES="$fixtures" python3 tests/integration/temporal/payment-recovery.py "${compose[@]}"

export PAYMENTS_TEST_MQ_ADDRESS
mq_port="$("${compose[@]}" port clearing-mq 61616)"
PAYMENTS_TEST_MQ_ADDRESS="tcp://127.0.0.1:${mq_port##*:}"
./gradlew :payments-service:test --tests "*MqClearingRecoveryTest"

inbox="$("${compose[@]}" exec -T payments-postgres psql -At -U payments -d payments \
  -v ON_ERROR_STOP=1 -c "SELECT count(*) FROM payments.clearing_inbox WHERE payment_id = '$payment_id' AND kind = 'SETTLED'")"
[[ "$inbox" == 1 ]]

# Quiesce both databases before taking the paired backup. The project is disposable.
"${compose[@]}" stop frontend api-gateway payments-service payments-worker clearing-simulator transactions-service accounts-service
restore_report="tests/integration/temporal/results/$project-restore.json"
mkdir -p "$(dirname "$restore_report")"
bash tests/integration/temporal/verify-financial-restore.sh \
  "$("${compose[@]}" ps -q postgres)" "$("${compose[@]}" ps -q payments-postgres)" "$restore_report" \
  "$("${compose[@]}" ps -q redpanda)"
