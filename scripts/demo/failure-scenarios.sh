#!/bin/sh
# Intentional failures for the local demo. Run from the repository root.
set -eu

compose() {
  docker compose "$@"
}

case "${1:-}" in
  fraud-closed)
    echo "Recreate transactions-service so a fraud outage rejects the movement. This is already the default."
    FRAUD_FAIL_OPEN=false compose up -d --no-deps --force-recreate transactions-service
    compose stop fraud-service
    echo "A deposit now returns 400. Jaeger shows fraud.decision=fail-closed on transactions-service."
    echo "Restore fraud with: docker compose start fraud-service"
    echo "Fail open only for a demo: FRAUD_FAIL_OPEN=true docker compose up -d --no-deps --force-recreate transactions-service"
    ;;
  kafka-pause)
    echo "Pause Redpanda. New transfers still commit. transactions_outbox_oldest_seconds climbs"
    echo "on the Banking App dashboard (Grafana http://localhost:3001, panel Unpublished outbox age)."
    compose pause redpanda
    echo "Resume with: $0 kafka-resume"
    ;;
  kafka-resume)
    compose unpause redpanda
    echo "Redpanda is running. The outbox publisher drains transaction-events."
    ;;
  temporal)
    echo "Worker replacement is covered by the integration suite, which prints a Temporal history URL."
    echo "From the repository root: make test-integration-temporal-ui"
    echo "See tests/integration/README.md for the dispute and statement recovery steps."
    ;;
  payments)
    echo "Start the overlay, then submit an AUD payment from /payments."
    echo "docker compose -f docker-compose.yml -f docker-compose.mq.yml -f docker-compose.payments.yml up -d"
    echo "Reconciliation mismatches stay visible as payments_reconciliation_discrepancies"
    echo "and on /operations/payments for an allowlisted operator."
    echo "Set PAYMENTS_ENABLED=true on the simulation client only after OUTBOUND_PAYMENTS_ENABLED=true."
    ;;
  *)
    echo "Usage: $0 fraud-closed|kafka-pause|kafka-resume|temporal|payments" >&2
    exit 1
    ;;
esac
