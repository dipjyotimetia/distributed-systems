#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/../../.."
root="$(mktemp -d /tmp/banking-payment-kind.XXXXXX)"
cluster="payments-$(openssl rand -hex 5)"
export KUBECONFIG="$root/kubeconfig"
export MICROSVC_PAYMENT_EVIDENCE="$root/evidence.json"
export MICROSVC_PAYMENTS=1
pids=()
cleanup() {
  local status=$?
  for pid in "${pids[@]}"; do kill "$pid" 2>/dev/null || true; done
  if [[ "$status" != 0 ]]; then
    kubectl -n banking-app get pods,jobs,pvc || true
    for app in banking-payments banking-payments-worker banking-clearing-simulator; do
      kubectl -n banking-app logs "deployment/$app" --tail=40 || true
    done
  fi
  kind delete cluster --name "$cluster" || true
  rm -r "$root"
}
trap cleanup EXIT

kind create cluster --name "$cluster" --wait 120s
build_image() {
  if ! docker build "$@"; then
    sleep 5
    docker build "$@"
  fi
}
images=()
for service in accounts-service ai-service api-gateway disputes-service fraud-service notification-service transactions-service user-service; do
  image="microsvc-journeys/$service:checkout"
  case "$service" in
    accounts-service|api-gateway|payments-service|transactions-service) build_image -t "$image" -f "backend/$service/Dockerfile" . ;;
    *) build_image -t "$image" "backend/$service" ;;
  esac
  images+=("$image")
done
build_image -t microsvc-journeys/frontend:checkout frontend
build_image -t microsvc-journeys/payments-service:checkout -f backend/payments-service/Dockerfile .
build_image -t microsvc-journeys/provider-fixtures:checkout -f tests/integration/temporal/stack/Dockerfile .
kind load docker-image --name "$cluster" "${images[@]}" \
  microsvc-journeys/frontend:checkout microsvc-journeys/payments-service:checkout microsvc-journeys/provider-fixtures:checkout


kubectl create namespace banking-app
kubectl apply -k kubernetes/observability
kubectl -n banking-app apply -k kubernetes/testing/payment-journeys
secret() {
  kubectl -n banking-app create secret generic "$@"
}

temporal_password="$(openssl rand -hex 24)"
disputes_password="$(openssl rand -hex 24)"
payments_password="$(openssl rand -hex 24)"
clearing_password="$(openssl rand -hex 24)"
export BANKING_MQ_APP_PASSWORD="$(openssl rand -hex 24)"
secret banking-workflow-credentials \
  --from-literal=temporal-password="$temporal_password" \
  --from-literal=disputes-password="$disputes_password" \
  --from-literal=disputes-database-url="postgres://disputes_service_user:$disputes_password@banking-postgres:5432/banking_app?sslmode=disable"
secret banking-payments-db \
  --from-literal=PAYMENTS_DB_URL=jdbc:postgresql://banking-payments-postgres:5432/payments \
  --from-literal=PAYMENTS_DB_USER=payments --from-literal=PAYMENTS_DB_PASSWORD="$payments_password"
secret banking-clearing-db \
  --from-literal=CLEARING_DB_URL=jdbc:postgresql://banking-clearing-postgres:5432/clearing \
  --from-literal=CLEARING_DB_USER=clearing --from-literal=CLEARING_DB_PASSWORD="$clearing_password"
secret banking-payment-movement --from-literal=MOVEMENT_SECRET="$(openssl rand -hex 32)"
secret banking-payments-worker \
  --from-literal=PAYMENTS_COMPLIANCE_URL=http://provider-fixtures:8081/searches \
  --from-literal=PAYMENTS_COMPLIANCE_TOKEN=synthetic-token
for client in payments clearing; do
  secret "banking-$client-mq" --from-literal=MQ_PASSWORD="$BANKING_MQ_APP_PASSWORD"
done
for role in reader writer; do
  secret "banking-settlement-$role" --from-literal=SETTLEMENT_BUCKET=settlements-test \
    --from-literal=SETTLEMENT_S3_ENDPOINT=http://provider-fixtures:8081 \
    --from-literal=AWS_ACCESS_KEY_ID="synthetic-$role" --from-literal=AWS_SECRET_ACCESS_KEY="synthetic-$role"
done

kubectl -n banking-app wait --for=condition=complete job/banking-workflow-bootstrap-v3 --timeout=5m
for resource in statefulset/banking-clearing-mq statefulset/banking-payments-postgres-19 statefulset/banking-clearing-postgres-19 \
  deployment/banking-kafka deployment/mongodb deployment/banking-temporal deployment/provider-fixtures \
  deployment/banking-gateway deployment/banking-frontend deployment/banking-accounts deployment/banking-user \
  deployment/banking-fraud deployment/banking-transactions deployment/banking-payments deployment/banking-notification \
  deployment/banking-payments-worker; do
  kubectl -n banking-app rollout status "$resource" --timeout=5m
done

broker() { kubectl -n banking-app exec deployment/banking-kafka -- rpk "$@" -X brokers=banking-kafka:9092; }
for topic in transaction-events payment-events.v1; do
  broker topic describe "$topic" || broker topic create "$topic" --partitions 1 --replicas 1
done

read -r cutoff date < <(python3 - <<'PY'
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo
end = datetime.now(ZoneInfo("Australia/Sydney")) + timedelta(minutes=4)
print(end.strftime("%H:%M"), end.date().isoformat())
PY
)
kubectl -n banking-app patch configmap banking-payments-config --type merge -p "{\"data\":{\"SETTLEMENT_CUTOFF\":\"$cutoff\",\"SETTLEMENT_BUSINESS_DATE\":\"$date\"}}"
kubectl -n banking-app scale deployment/banking-clearing-simulator --replicas=1
kubectl -n banking-app rollout status deployment/banking-clearing-simulator --timeout=2m

forward() {
  local name="$1" port="$2" namespace="${3:-banking-app}" logfile="$root/$1.log" local_port
  kubectl -n "$namespace" port-forward "svc/$name" ":$port" >"$logfile" 2>&1 &
  pids+=("$!")
  for ((attempt = 0; attempt < 30; attempt++)); do
    local_port="$(sed -nE 's/^Forwarding from 127.0.0.1:([0-9]+) .*/\1/p' "$logfile" | head -1)"
    if [[ -n "$local_port" ]]; then forwarded="$local_port"; return; fi
    sleep 1
  done
  cat "$logfile" >&2
  return 1
}
forward banking-frontend 80
export MICROSVC_FRONTEND="http://127.0.0.1:$forwarded"
forward banking-gateway 80
export MICROSVC_GATEWAY="http://127.0.0.1:$forwarded"
forward banking-notification 80
export MICROSVC_NOTIFICATIONS="http://127.0.0.1:$forwarded"
forward provider-fixtures 8081
fixtures="http://127.0.0.1:$forwarded"
forward banking-clearing-mq 61616
export PAYMENTS_TEST_MQ_ADDRESS="tcp://127.0.0.1:$forwarded"

kubectl -n observability rollout status deployment/jaeger --timeout=2m
kubectl -n observability rollout status deployment/otel-collector --timeout=2m
forward jaeger 16686 observability
jaeger="http://127.0.0.1:$forwarded"

(cd frontend && npm run test:e2e:real-backend -- payments.real-backend.spec.ts --workers=1)
timeout 30 kubectl -n banking-app exec deployment/banking-kafka -- rpk topic consume payment-events.v1 \
  -X brokers=banking-kafka:9092 -o :end --format '%k\t%v\n' |
  python3 tests/integration/verify-payment-event.py "$MICROSVC_PAYMENT_EVIDENCE"
python3 tests/integration/verify-payment-trace.py "$jaeger" "$MICROSVC_PAYMENT_EVIDENCE"

object="$fixtures/settlements-test/settlement/aud/$date.dat"
for ((attempt = 0; attempt < 100; attempt++)); do
  if curl -fsS -o /dev/null "$object" 2>/dev/null; then break; fi
  sleep 3
done
curl -fsS "$object" | python3 tests/integration/verify-payment-settlement.py "$MICROSVC_PAYMENT_EVIDENCE"
for run in original replay; do
  job="payment-reconciliation-$run"
  kubectl -n banking-app create job --from=cronjob/banking-payments-reconciliation "$job"
  kubectl -n banking-app wait --for=condition=complete "job/$job" --timeout=2m
done
payment_id="$(python3 -c 'import json,sys,uuid; print(uuid.UUID(json.load(open(sys.argv[1]))["paymentId"]))' "$MICROSVC_PAYMENT_EVIDENCE")"
movement="$(kubectl -n banking-app exec deployment/banking-postgres -- psql -At -U postgres -d banking_app \
  -v ON_ERROR_STOP=1 -c "SELECT state || '|' || amount FROM accounts_service.movement_reservations WHERE operation_id = '$payment_id'")"
[[ "$movement" == 'COMMITTED|25.00' ]]
matched="$(kubectl -n banking-app exec banking-payments-postgres-19-0 -- psql -At -U payments -d payments \
  -v ON_ERROR_STOP=1 -c "SELECT count(*) FROM payments.reconciliation_result WHERE payment_id = '$payment_id' AND outcome = 'MATCHED'")"
[[ "$matched" == 1 ]]
./gradlew :payments-service:test --tests "*MqClearingRecoveryTest"
inbox="$(kubectl -n banking-app exec banking-payments-postgres-19-0 -- psql -At -U payments -d payments \
  -v ON_ERROR_STOP=1 -c "SELECT count(*) FROM payments.clearing_inbox WHERE payment_id = '$payment_id' AND kind = 'SETTLED'")"
[[ "$inbox" == 1 ]]
MICROSVC_PAYMENT_FIXTURES="$fixtures" python3 tests/integration/temporal/payment-recovery.py --kind
