#!/usr/bin/env bash
# WireMock offline replay harness for a backend service.
#
# Starts WireMock with committed stubs, builds/runs the SUT, drives the request
# suite, then runs schema + property gates. No vendor traffic-replay CLI required.
#
# Usage: scripts/replay/replay-service.sh <service>
# Example: scripts/replay/replay-service.sh api-gateway
set -euo pipefail

SERVICE="${1:-}"
if [ -z "$SERVICE" ]; then
  echo "usage: $0 <service>" >&2
  exit 2
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
SUITE_DIR="${PROJECT_ROOT}/tests/replay/services/${SERVICE}"

if [ ! -f "${SUITE_DIR}/requests.json" ]; then
  echo "error: missing ${SUITE_DIR}/requests.json" >&2
  exit 2
fi

export REPLAY_JWT_SECRET="${REPLAY_JWT_SECRET:-replay-golden-test-secret-key-change-this-32bytes-min-0987654321}"

WIREMOCK_PORT="${WIREMOCK_PORT:-18080}"
APP_PORT="${APP_PORT:-8080}"
RESULTS_DIR="${PROJECT_ROOT}/backend/${SERVICE}/replay-results/replay-$(date +%Y-%m-%d_%H-%M-%S)"
mkdir -p "$RESULTS_DIR"

if [ "$SERVICE" = "api-gateway" ]; then
  SERVICE_DIR="${PROJECT_ROOT}/backend/api-gateway"
  BUILD_CMD='../../gradlew -p ../.. -q :api-gateway:clean :api-gateway:bootJar'
  JAR_GLOB='build/libs/application.jar'
else
  echo "error: unsupported service '$SERVICE' (use run-user-service-validation.sh for user-service)" >&2
  exit 2
fi

if command -v nc >/dev/null 2>&1; then
  if nc -z 127.0.0.1 "$WIREMOCK_PORT" 2>/dev/null; then
    echo "error: WireMock port $WIREMOCK_PORT is in use" >&2
    exit 1
  fi
  if nc -z 127.0.0.1 "$APP_PORT" 2>/dev/null; then
    echo "error: app port $APP_PORT is in use" >&2
    exit 1
  fi
fi

APP_PID=""
WM_CID="replay-wiremock-$$"
cleanup() {
  [ -n "${APP_PID}" ] && kill "$APP_PID" 2>/dev/null || true
  docker stop "$WM_CID" >/dev/null 2>&1 || true
  [ -n "${APP_PID}" ] && wait "$APP_PID" 2>/dev/null || true
}
trap cleanup EXIT

echo "Starting WireMock on :${WIREMOCK_PORT}..."
docker run --rm -d --name "$WM_CID" \
  -p "127.0.0.1:${WIREMOCK_PORT}:8080" \
  -v "${SUITE_DIR}/stubs:/home/wiremock/mappings:ro" \
  wiremock/wiremock:3.13.1 --disable-banner >/dev/null

for _ in $(seq 1 30); do
  if curl -fsS "http://127.0.0.1:${WIREMOCK_PORT}/__admin/mappings" >/dev/null 2>&1; then
    break
  fi
  sleep 1
done

cd "$SERVICE_DIR"
if [ "${REPLAY_SKIP_BUILD:-}" = "1" ] && ls -1 $JAR_GLOB >/dev/null 2>&1; then
  echo "REPLAY_SKIP_BUILD=1: reusing existing artifact"
else
  echo "Building $SERVICE..."
  eval "$BUILD_CMD"
fi
JAR=$(ls -1 $JAR_GLOB)
echo "Using artifact: $JAR"

echo "Starting $SERVICE..."
env \
  JWT_SECRET="$REPLAY_JWT_SECRET" \
  USER_SERVICE_URL="http://127.0.0.1:${WIREMOCK_PORT}" \
  ACCOUNTS_SERVICE_URL="http://127.0.0.1:${WIREMOCK_PORT}" \
  TRANSACTIONS_SERVICE_URL="http://127.0.0.1:${WIREMOCK_PORT}" \
  java \
    -Dgateway.rate-limiter.enabled=false \
    -Dfault.injection.rate=0.0 \
    -Dmanagement.health.redis.enabled=false \
    -Dspring.data.redis.host=127.0.0.1 \
    -Dspring.data.redis.port=6379 \
    -Dotel.sdk.disabled=true \
    -Dotel.traces.exporter=none \
    -Dotel.metrics.exporter=none \
    -Dotel.logs.exporter=none \
    -Dspring.autoconfigure.exclude=io.opentelemetry.instrumentation.spring.autoconfigure.OpenTelemetryAutoConfiguration,io.opentelemetry.instrumentation.spring.autoconfigure.instrumentation.web.SpringWebInstrumentationAutoConfiguration,io.opentelemetry.instrumentation.spring.autoconfigure.instrumentation.webflux.SpringWebfluxInstrumentationAutoConfiguration \
    -jar "$JAR" >"${RESULTS_DIR}/app.log" 2>&1 &
APP_PID=$!

# Prefer liveness so Redis (disabled for offline replay) cannot keep readiness red.
HEALTH_PATHS=("/actuator/health/liveness" "/actuator/health" "/health")

READY=false
for _ in $(seq 1 90); do
  if ! kill -0 "$APP_PID" 2>/dev/null; then
    echo "$SERVICE exited early:"; tail -40 "${RESULTS_DIR}/app.log" || true
    exit 1
  fi
  for path in "${HEALTH_PATHS[@]}"; do
    code=$(curl -sS -o /dev/null -w "%{http_code}" "http://127.0.0.1:${APP_PORT}${path}" 2>/dev/null || echo 000)
    if [ "$code" = "200" ]; then READY=true; echo "$SERVICE healthy at ${path}"; break 2; fi
  done
  sleep 1
done
if [ "$READY" != true ]; then
  echo "$SERVICE failed to become ready:"; tail -60 "${RESULTS_DIR}/app.log" || true
  exit 1
fi

python3 "${SCRIPT_DIR}/jwt_resign.py" "${SUITE_DIR}/requests.json" "${RESULTS_DIR}/requests.signed.json"

echo "=== Gate 1: drive request suite (status codes) ==="
set +e
python3 "${SCRIPT_DIR}/drive_suite.py" \
  --suite "${RESULTS_DIR}/requests.signed.json" \
  --base-url "http://127.0.0.1:${APP_PORT}" \
  --out "${RESULTS_DIR}/observed.json"
REPLAY_RC=$?
set -e

echo ""
echo "=== Gate 2: schema validation + contract coverage ==="
set +e
python3 "${SCRIPT_DIR}/schema_validate.py" \
  --suite "${SUITE_DIR}/requests.json" \
  --observed "${RESULTS_DIR}/observed.json" \
  --require-coverage
SCHEMA_RC=$?
set -e

PROP_RC=0
if [ -f "${SUITE_DIR}/invariants.json" ]; then
  echo ""
  echo "=== Gate 3: property / business-invariant validation ==="
  set +e
  python3 "${SCRIPT_DIR}/property_validate.py" \
    --rules "${SUITE_DIR}/invariants.json" \
    --observed "${RESULTS_DIR}/observed.json"
  PROP_RC=$?
  set -e
fi

echo ""
echo "Reports: ${RESULTS_DIR}"
if [ "$REPLAY_RC" -ne 0 ] || [ "$SCHEMA_RC" -ne 0 ] || [ "$PROP_RC" -ne 0 ]; then
  exit 1
fi
exit 0
