#!/usr/bin/env bash
# Offline user-service validation: real Postgres + WireMock identity stubs + request suite.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
SERVICE_DIR="${PROJECT_ROOT}/backend/user-service"
SUITE_DIR="${PROJECT_ROOT}/tests/replay/services/user-service"
RESULTS_DIR="${SERVICE_DIR}/replay-results/replay-$(date +%Y-%m-%d_%H-%M-%S)"
mkdir -p "$RESULTS_DIR"

WIREMOCK_PORT="${WIREMOCK_PORT:-18081}"
APP_PORT="${APP_PORT:-8080}"
PGHOST="${PGHOST:-127.0.0.1}"
PGPORT="${PGPORT:-5432}"
PGUSER="${PGUSER:-postgres}"
PGPASSWORD="${PGPASSWORD:-password}"
PGDATABASE="${PGDATABASE:-banking_test}"
export PGPASSWORD

if command -v nc >/dev/null 2>&1; then
  if ! nc -z "$PGHOST" "$PGPORT" 2>/dev/null; then
    echo "error: Postgres is not reachable at ${PGHOST}:${PGPORT}" >&2
    exit 1
  fi
  if nc -z 127.0.0.1 "$WIREMOCK_PORT" 2>/dev/null; then
    echo "error: WireMock port $WIREMOCK_PORT is in use" >&2
    exit 1
  fi
  if nc -z 127.0.0.1 "$APP_PORT" 2>/dev/null; then
    echo "error: app port $APP_PORT is in use" >&2
    exit 1
  fi
fi

psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE" -c \
  "CREATE SCHEMA IF NOT EXISTS user_service;" >/dev/null

APP_PID=""
WM_CID="replay-wiremock-user-$$"
cleanup() {
  [ -n "${APP_PID}" ] && kill "$APP_PID" 2>/dev/null || true
  docker stop "$WM_CID" >/dev/null 2>&1 || true
  [ -n "${APP_PID}" ] && wait "$APP_PID" 2>/dev/null || true
}
trap cleanup EXIT

echo "Starting WireMock identity stubs on :${WIREMOCK_PORT}..."
docker run --rm -d --name "$WM_CID" \
  -p "127.0.0.1:${WIREMOCK_PORT}:8080" \
  -v "${SUITE_DIR}/stubs:/home/wiremock/mappings:ro" \
  wiremock/wiremock:3.13.1 --disable-banner >/dev/null

for _ in $(seq 1 30); do
  curl -fsS "http://127.0.0.1:${WIREMOCK_PORT}/__admin/mappings" >/dev/null 2>&1 && break
  sleep 1
done

cd "$SERVICE_DIR"
echo "Building user-service..."
dotnet publish -c Release -o publish
DLL_FILE="publish/user-service-dotnet.dll"
[ -f "$DLL_FILE" ] || { echo "error: missing $DLL_FILE"; exit 1; }

echo "Starting user-service..."
env \
  ASPNETCORE_URLS="http://127.0.0.1:${APP_PORT}" \
  DB_HOST="$PGHOST" \
  DB_PORT="$PGPORT" \
  DB_NAME="$PGDATABASE" \
  DB_USERNAME="$PGUSER" \
  DB_PASSWORD="$PGPASSWORD" \
  DB_SCHEMA=user_service \
  JWT_SECRET="${REPLAY_JWT_SECRET:-replay-golden-test-secret-key-change-this-32bytes-min-0987654321}" \
  SOCURE_BASE_URL="http://127.0.0.1:${WIREMOCK_PORT}/socure" \
  JUMIO_BASE_URL="http://127.0.0.1:${WIREMOCK_PORT}/jumio" \
  HIBP_BASE_URL="http://127.0.0.1:${WIREMOCK_PORT}/hibp" \
  OTEL_SDK_DISABLED=true \
  dotnet "$DLL_FILE" >"${RESULTS_DIR}/app.log" 2>&1 &
APP_PID=$!

READY=false
for _ in $(seq 1 60); do
  if ! kill -0 "$APP_PID" 2>/dev/null; then
    echo "user-service exited early:"; tail -40 "${RESULTS_DIR}/app.log" || true
    exit 1
  fi
  code=$(curl -sS -o /dev/null -w "%{http_code}" "http://127.0.0.1:${APP_PORT}/actuator/health" 2>/dev/null || echo 000)
  if [ "$code" = "200" ]; then READY=true; break; fi
  sleep 1
done
if [ "$READY" != true ]; then
  echo "user-service failed to become ready:"; tail -60 "${RESULTS_DIR}/app.log" || true
  exit 1
fi

echo "=== Drive request suite ==="
set +e
python3 "${SCRIPT_DIR}/drive_suite.py" \
  --suite "${SUITE_DIR}/requests.json" \
  --base-url "http://127.0.0.1:${APP_PORT}" \
  --out "${RESULTS_DIR}/observed.json"
REPLAY_RC=$?
set -e

echo "Reports: ${RESULTS_DIR}"
exit "$REPLAY_RC"
