#!/usr/bin/env bash
# WireMock offline replay harness for a backend service.
#
# Per-service config: scripts/replay/services/<service>.env
# Fixtures:           tests/replay/services/<service>/{requests.json,stubs/,invariants.json?}
#
# Usage: scripts/replay/replay-service.sh <service>
set -euo pipefail

SERVICE="${1:-}"
if [ -z "$SERVICE" ]; then
  echo "usage: $0 <service>" >&2
  exit 2
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
SUITE_DIR="${PROJECT_ROOT}/tests/replay/services/${SERVICE}"
CONFIG="${SCRIPT_DIR}/services/${SERVICE}.env"

if [ ! -f "${SUITE_DIR}/requests.json" ]; then
  echo "error: missing ${SUITE_DIR}/requests.json" >&2
  exit 2
fi
if [ ! -f "$CONFIG" ]; then
  echo "error: missing config $CONFIG" >&2
  exit 2
fi

export REPLAY_JWT_SECRET="${REPLAY_JWT_SECRET:-replay-golden-test-secret-key-change-this-32bytes-min-0987654321}"
export WIREMOCK_PORT="${WIREMOCK_PORT:-18080}"
export PGHOST="${PGHOST:-127.0.0.1}"
export PGPORT="${PGPORT:-5432}"
export PGUSER="${PGUSER:-postgres}"
export PGPASSWORD="${PGPASSWORD:-password}"
export PGDATABASE="${PGDATABASE:-banking_test}"
export MONGO_PORT="${MONGO_PORT:-27017}"
export KAFKA_PORT="${KAFKA_PORT:-19092}"
export SCHEMA_REGISTRY_PORT="${SCHEMA_REGISTRY_PORT:-18081}"

# Defaults overridden by service config.
RUNTIME=java
BUILD_CMD=''
ARTIFACT_GLOB=''
APP_PORT=8080
HEALTH_PATHS=("/actuator/health")
NEEDS_POSTGRES=0
NEEDS_MONGO=0
NEEDS_KAFKA=0
POSTGRES_SCHEMAS=()
JWT_RESIGN=0
MINT_JWT=0
SCHEMA_VALIDATE=1
PROPERTY_VALIDATE=1
APP_ENV=()
APP_FLAGS=()
PYTHON_MODULE='main:app'
SERVICE_DIR_REL="backend/${SERVICE}"

# shellcheck source=/dev/null
source "$CONFIG"

SERVICE_DIR="${PROJECT_ROOT}/${SERVICE_DIR_REL}"
RESULTS_DIR="${SERVICE_DIR}/replay-results/replay-$(date +%Y-%m-%d_%H-%M-%S)"
mkdir -p "$RESULTS_DIR"

if command -v nc >/dev/null 2>&1; then
  if nc -z 127.0.0.1 "$WIREMOCK_PORT" 2>/dev/null; then
    echo "error: WireMock port $WIREMOCK_PORT is in use" >&2
    exit 1
  fi
  if nc -z 127.0.0.1 "$APP_PORT" 2>/dev/null; then
    echo "error: app port $APP_PORT is in use" >&2
    exit 1
  fi
  if [ "$NEEDS_POSTGRES" = "1" ] && ! nc -z "$PGHOST" "$PGPORT" 2>/dev/null; then
    echo "error: Postgres is not reachable at ${PGHOST}:${PGPORT}" >&2
    exit 1
  fi
fi

APP_PID=""
WM_CID="replay-wm-${SERVICE}-$$"
MONGO_CID=""
KAFKA_CID=""
cleanup() {
  [ -n "${APP_PID}" ] && kill "$APP_PID" 2>/dev/null || true
  docker stop "$WM_CID" >/dev/null 2>&1 || true
  [ -n "$MONGO_CID" ] && docker stop "$MONGO_CID" >/dev/null 2>&1 || true
  [ -n "$KAFKA_CID" ] && docker stop "$KAFKA_CID" >/dev/null 2>&1 || true
  [ -n "${APP_PID}" ] && wait "$APP_PID" 2>/dev/null || true
}
trap cleanup EXIT

if [ "$NEEDS_POSTGRES" = "1" ]; then
  export PGPASSWORD
  for schema in "${POSTGRES_SCHEMAS[@]+"${POSTGRES_SCHEMAS[@]}"}"; do
    psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE" -v ON_ERROR_STOP=1 -c \
      "CREATE SCHEMA IF NOT EXISTS ${schema};" >/dev/null
  done
fi

if [ "$NEEDS_MONGO" = "1" ]; then
  MONGO_CID="replay-mongo-${SERVICE}-$$"
  echo "Starting MongoDB on :${MONGO_PORT}..."
  docker run --rm -d --name "$MONGO_CID" -p "127.0.0.1:${MONGO_PORT}:27017" mongo:7 >/dev/null
  for _ in $(seq 1 30); do
    if docker exec "$MONGO_CID" mongosh --quiet --eval 'db.runCommand({ ping: 1 })' >/dev/null 2>&1; then
      break
    fi
    sleep 1
  done
fi

if [ "$NEEDS_KAFKA" = "1" ]; then
  KAFKA_CID="replay-kafka-${SERVICE}-$$"
  echo "Starting Redpanda on :${KAFKA_PORT}..."
  docker run --rm -d --name "$KAFKA_CID" \
    -p "127.0.0.1:${KAFKA_PORT}:9092" \
    -p "127.0.0.1:${SCHEMA_REGISTRY_PORT}:8081" \
    docker.redpanda.com/redpandadata/redpanda:v26.2.2 \
    redpanda start --overprovisioned --smp 1 --memory 512M --reserve-memory 0M \
      --node-id 0 --check=false \
      --kafka-addr=0.0.0.0:9092 \
      --advertise-kafka-addr=127.0.0.1:${KAFKA_PORT} \
      --schema-registry-addr=0.0.0.0:8081 \
      >/dev/null
  for _ in $(seq 1 40); do
    if docker exec "$KAFKA_CID" rpk cluster health >/dev/null 2>&1; then
      break
    fi
    sleep 1
  done
fi

echo "Starting WireMock on :${WIREMOCK_PORT}..."
STUBS_MOUNT="${SUITE_DIR}/stubs"
if [ ! -d "$STUBS_MOUNT" ]; then
  mkdir -p "${RESULTS_DIR}/empty-stubs"
  STUBS_MOUNT="${RESULTS_DIR}/empty-stubs"
fi
docker run --rm -d --name "$WM_CID" \
  -p "127.0.0.1:${WIREMOCK_PORT}:8080" \
  -v "${STUBS_MOUNT}:/home/wiremock/mappings:ro" \
  wiremock/wiremock:3.13.1 --disable-banner >/dev/null

for _ in $(seq 1 30); do
  if curl -fsS "http://127.0.0.1:${WIREMOCK_PORT}/__admin/mappings" >/dev/null 2>&1; then
    break
  fi
  sleep 1
done

# Re-source so APP_ENV expands with companion ports now known.
# shellcheck source=/dev/null
source "$CONFIG"

cd "$SERVICE_DIR"
if [ "${REPLAY_SKIP_BUILD:-}" != "1" ]; then
  echo "Building $SERVICE..."
  eval "$BUILD_CMD"
elif [ -n "$ARTIFACT_GLOB" ] && ls -1 $ARTIFACT_GLOB >/dev/null 2>&1; then
  echo "REPLAY_SKIP_BUILD=1: reusing existing artifact"
else
  echo "Building $SERVICE..."
  eval "$BUILD_CMD"
fi

echo "Starting $SERVICE ($RUNTIME)..."
case "$RUNTIME" in
  java)
    ARTIFACT=$(ls -1 $ARTIFACT_GLOB)
    echo "Using artifact: $ARTIFACT"
    env "${APP_ENV[@]}" java "${APP_FLAGS[@]+"${APP_FLAGS[@]}"}" -jar "$ARTIFACT" \
      >"${RESULTS_DIR}/app.log" 2>&1 &
    ;;
  dotnet)
    ARTIFACT=$(ls -1 $ARTIFACT_GLOB)
    echo "Using artifact: $ARTIFACT"
    env "${APP_ENV[@]}" dotnet "$ARTIFACT" >"${RESULTS_DIR}/app.log" 2>&1 &
    ;;
  go)
    ARTIFACT=$(ls -1 $ARTIFACT_GLOB)
    echo "Using artifact: $ARTIFACT"
    env "${APP_ENV[@]}" "./${ARTIFACT}" >"${RESULTS_DIR}/app.log" 2>&1 &
    ;;
  python)
    env "${APP_ENV[@]}" python3 -m uvicorn "${PYTHON_MODULE}" --host 127.0.0.1 --port "$APP_PORT" \
      >"${RESULTS_DIR}/app.log" 2>&1 &
    ;;
  *)
    echo "error: unsupported RUNTIME=$RUNTIME" >&2
    exit 2
    ;;
esac
APP_PID=$!

READY=false
for _ in $(seq 1 90); do
  if ! kill -0 "$APP_PID" 2>/dev/null; then
    echo "$SERVICE exited early:"; tail -50 "${RESULTS_DIR}/app.log" || true
    exit 1
  fi
  for path in "${HEALTH_PATHS[@]}"; do
    code=$(curl -sS -o /dev/null -w "%{http_code}" "http://127.0.0.1:${APP_PORT}${path}" 2>/dev/null || echo 000)
    if [ "$code" = "200" ]; then READY=true; echo "$SERVICE healthy at ${path}"; break 2; fi
  done
  sleep 1
done
if [ "$READY" != true ]; then
  echo "$SERVICE failed to become ready:"; tail -80 "${RESULTS_DIR}/app.log" || true
  exit 1
fi

SUITE_FILE="${SUITE_DIR}/requests.json"
if [ "$JWT_RESIGN" = "1" ]; then
  python3 "${SCRIPT_DIR}/jwt_resign.py" "$SUITE_FILE" "${RESULTS_DIR}/requests.prepared.json"
  SUITE_FILE="${RESULTS_DIR}/requests.prepared.json"
elif [ "$MINT_JWT" = "1" ]; then
  python3 "${SCRIPT_DIR}/prepare_suite.py" "$SUITE_FILE" "${RESULTS_DIR}/requests.prepared.json"
  SUITE_FILE="${RESULTS_DIR}/requests.prepared.json"
fi

echo "=== Gate 1: drive request suite (status codes) ==="
set +e
python3 "${SCRIPT_DIR}/drive_suite.py" \
  --suite "$SUITE_FILE" \
  --base-url "http://127.0.0.1:${APP_PORT}" \
  --out "${RESULTS_DIR}/observed.json"
REPLAY_RC=$?
set -e

SCHEMA_RC=0
if [ "$SCHEMA_VALIDATE" = "1" ]; then
  echo ""
  echo "=== Gate 2: schema validation + contract coverage ==="
  set +e
  python3 "${SCRIPT_DIR}/schema_validate.py" \
    --suite "${SUITE_DIR}/requests.json" \
    --observed "${RESULTS_DIR}/observed.json" \
    --require-coverage
  SCHEMA_RC=$?
  set -e
fi

PROP_RC=0
if [ "$PROPERTY_VALIDATE" = "1" ] && [ -f "${SUITE_DIR}/invariants.json" ]; then
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
