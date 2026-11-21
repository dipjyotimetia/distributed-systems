#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/../../.."
suite=tests/integration/temporal
if [[ -z "${MICROSVC_SCENARIO:-}" ]]; then
  # Orchestrate scenarios on one project name so Docker layer/image cache is
  # retained. Volumes are still recreated per scenario for isolation; images are
  # kept (no --rmi local) via MICROSVC_KEEP_IMAGES.
  export MICROSVC_SHARED_PROJECT="${MICROSVC_SHARED_PROJECT:-temporal-$(openssl rand -hex 8)}"
  export MICROSVC_KEEP_IMAGES=1
  status=0
  MICROSVC_SCENARIO=transfer-worker-replacement bash "$suite/run.sh" || status=$?
  if [[ -z "${TEMPORAL_TEST_UI_PORT:-}" ]]; then
    MICROSVC_SCENARIO=identity MICROSVC_REPORT_SUFFIX="${MICROSVC_REPORT:+identity}" bash "$suite/run.sh" || status=$?
    MICROSVC_SCENARIO=ledger MICROSVC_REPORT_SUFFIX="${MICROSVC_REPORT:+ledger}" bash "$suite/run.sh" || status=$?
    MICROSVC_SCENARIO=consumer-recovery MICROSVC_REPORT_SUFFIX="${MICROSVC_REPORT:+consumer-recovery}" bash "$suite/run.sh" || status=$?
    MICROSVC_SCENARIO=statement-export MICROSVC_REPORT_SUFFIX="${MICROSVC_REPORT:+statement-export}" bash "$suite/run.sh" || status=$?
    MICROSVC_SCENARIO=ai MICROSVC_REPORT_SUFFIX="${MICROSVC_REPORT:+ai}" bash "$suite/run.sh" || status=$?
    if [[ "${MICROSVC_BROWSER:-0}" == 1 ]]; then
      MICROSVC_SCENARIO=disputes MICROSVC_REPORT_SUFFIX="${MICROSVC_REPORT:+disputes}" bash "$suite/run.sh" || status=$?
    fi
  fi
  # Final image sweep once all scenarios finished.
  docker compose -p "$MICROSVC_SHARED_PROJECT" -f docker-compose.yml -f "$suite/stack/compose.yml" \
    down --volumes --remove-orphans --rmi local >/dev/null 2>&1 || true
  exit "$status"
fi
if [[ "$MICROSVC_SCENARIO" == statement-export ]]; then
  export MICROSVC_STATEMENT_BUCKET=statements-test
fi
if [[ "$MICROSVC_SCENARIO" == consumer-recovery ]]; then
  export MICROSVC_FAILED_PROVIDER=slack
fi
project="${MICROSVC_SHARED_PROJECT:-temporal-$(openssl rand -hex 8)}"
report="${MICROSVC_REPORT:-$PWD/$suite/results/$project.json}"
if [[ -n "${MICROSVC_REPORT_SUFFIX:-}" ]]; then
  report="${report%.json}-$MICROSVC_REPORT_SUFFIX.json"
fi
export MICROSVC_REPORT="$report"

compose=(docker compose -p "$project" -f docker-compose.yml -f "$suite/stack/compose.yml")
if [[ "$MICROSVC_SCENARIO" == disputes ]]; then
  compose+=(-f "$suite/stack/disputes.compose.yml")
fi
if [[ "$MICROSVC_SCENARIO" == consumer-recovery ]]; then
  compose+=(-f "$suite/stack/recovery.compose.yml")
fi
mkdir -p "$(dirname "$report")"
printf '{"scenario":"%s","workflow_passed":false}\n' "$MICROSVC_SCENARIO" >"$report"
stage=docker
browser_verdict=skipped
cleanup() {
  local status=$?
  local cleanup_passed=true
  if [[ "$status" != 0 ]]; then
    "${compose[@]}" ps -a || true
    "${compose[@]}" logs --tail 60 frontend api-gateway user-service accounts-service transactions-service ai-service notification-service || true
    if [[ "$MICROSVC_SCENARIO" == disputes ]]; then
      "${compose[@]}" logs --tail 40 postgres disputes-schema disputes-service disputes-worker disputes-temporal statement-worker || true
    fi
  fi
  # Keep images across orchestrated scenarios; only drop volumes/containers.
  local -a down_args=(--volumes --remove-orphans)
  if [[ "${MICROSVC_KEEP_IMAGES:-0}" != 1 ]]; then
    down_args+=(--rmi local)
  fi
  if ! "${compose[@]}" down "${down_args[@]}"; then
    cleanup_passed=false
    if [[ "$status" == 0 ]]; then
      status=1
      stage=cleanup
    fi
  fi
  python3 - "$report" "$status" "$stage" "$cleanup_passed" "$browser_verdict" <<'REPORT'
import json
import sys
from pathlib import Path

path, status, stage, cleanup_passed, browser = sys.argv[1:]
path = Path(path)
report = json.loads(path.read_text()) if path.exists() else {"workflow_passed": False}
if report.get("scenario") in ("identity", "disputes"):
    report["browser_verdict"] = browser
report.update(command_passed=status == "0", command_stage=stage, cleanup_passed=cleanup_passed == "true")
path.write_text(json.dumps(report, indent=2) + "\n")
REPORT
  printf 'Scenario %s report %s\n' "$MICROSVC_SCENARIO" "$report"
  exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

docker info --format '{{.ServerVersion}}' >/dev/null
stage=startup
# `up --build` builds the full dependency graph. Retry on Maven Central flakes;
# tear down between attempts so partial creates do not leave missing images.
compose_up_build() {
  local attempt=1
  local no_deps=()
  if [[ "${1:-}" == --no-deps ]]; then
    no_deps=(--no-deps)
    shift
  fi
  while (( attempt <= 5 )); do
    if "${compose[@]}" up -d ${no_deps[@]+"${no_deps[@]}"} --build "$@"; then
      return 0
    fi
    echo "compose up --build failed (attempt $attempt); retrying..." >&2
    "${compose[@]}" down --volumes --remove-orphans || true
    if (( attempt == 5 )); then
      return 1
    fi
    sleep $((attempt * 15))
    attempt=$((attempt + 1))
  done
}
if [[ "$MICROSVC_SCENARIO" == ai ]]; then
  compose_up_build --no-deps frontend ai-service provider-fixtures
elif [[ "$MICROSVC_SCENARIO" == disputes ]]; then
  compose_up_build frontend disputes-worker statement-worker notification-service
else
  compose_up_build frontend notification-service
  export MICROSVC_GATEWAY="http://$("${compose[@]}" port api-gateway 8080)"
  export MICROSVC_NOTIFICATIONS="http://$("${compose[@]}" port notification-service 8080)"
  export MICROSVC_BROKER_CONTAINER="$("${compose[@]}" ps -q redpanda)"
  export MICROSVC_POSTGRES_CONTAINER="$("${compose[@]}" ps -q postgres)"
  export MICROSVC_MONGO_CONTAINER="$("${compose[@]}" ps -q mongodb)"
  export MICROSVC_FRAUD_CONTAINER="$("${compose[@]}" ps -q fraud-service)"
  export MICROSVC_TRANSACTIONS_CONTAINER="$("${compose[@]}" ps -q transactions-service)"
  export MICROSVC_CONSUMER_CONTAINER="$("${compose[@]}" ps -q notification-service)"
fi
export MICROSVC_FRONTEND="http://$("${compose[@]}" port frontend 3000)"
if [[ "$MICROSVC_SCENARIO" == disputes ]]; then
  stage=browser
  export MICROSVC_DISPUTES_PROJECT="$project"
  export MICROSVC_GATEWAY="http://$("${compose[@]}" port api-gateway 8080)"
  browser_verdict=failed
  (cd frontend && npm run test:e2e:real-backend -- --grep '(transfer dispute|statement export) survives' --workers=1)
  browser_verdict=passed
  stage=shared-journeys
  notifications="http://$("${compose[@]}" port notification-service 8080)"
  python3 tests/integration/kubernetes-smoke.py "$MICROSVC_FRONTEND" - "$notifications"
  printf '{"scenario":"disputes","workflow_passed":true}\n' >"$report"
  stage=complete
  exit 0
fi
export MICROSVC_DELIVERIES="http://$("${compose[@]}" port provider-fixtures 8081)"
test_timeout=5m
if [[ -n "${TEMPORAL_TEST_UI_PORT:-}" ]]; then
  test_timeout=15m
fi
stage=workflow
(
  cd "$suite"
  go test -count=1 -timeout="$test_timeout" -run '^TestBankingIntegration$' -v .
  if [[ "$MICROSVC_SCENARIO" == ledger ]]; then
    go test -count=1 -timeout=3m -run '^TestVersionedMovements$' -v .
  fi
)
if [[ "$MICROSVC_SCENARIO" == ledger && "${MICROSVC_BROWSER:-0}" == 1 ]]; then
  stage=browser
  MICROSVC_MOVEMENTS=1 bash -c 'cd frontend && npm run test:e2e:real-backend -- movements.real-backend.spec.ts --workers=1'
fi
if [[ "$MICROSVC_SCENARIO" == identity && "${MICROSVC_BROWSER:-0}" == 1 ]]; then
  stage=browser
  browser_verdict=failed
  (cd frontend && npm run test:e2e:real-backend)
  browser_verdict=passed
fi
stage=complete
