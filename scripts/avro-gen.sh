#!/usr/bin/env bash
# Generate Go structs from contracts/avro using hamba avrogen (twmb/avro-compatible tags).
# Java SpecificRecords are generated at compile time by Gradle (see transactions/payments build.gradle.kts).
set -euo pipefail
cd "$(dirname "$0")/.."

AVROGEN_VERSION="${AVROGEN_VERSION:-v2.28.0}"
OUT_DIR=backend/notification-service/internal/events
mkdir -p "$OUT_DIR"

go run "github.com/hamba/avro/v2/cmd/avrogen@${AVROGEN_VERSION}" \
  -pkg events \
  -pkgdoc "Package events holds Avro record types generated from contracts/avro." \
  -strict-types \
  -o "$OUT_DIR/events_gen.go" \
  contracts/avro/transaction-event.avsc \
  contracts/avro/payment-event.avsc \
  contracts/avro/movement-lifecycle-event.avsc

gofmt -w "$OUT_DIR/events_gen.go"
echo "wrote $OUT_DIR/events_gen.go"
