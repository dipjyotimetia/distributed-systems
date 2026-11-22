# Avro event schemas

Confluent Schema Registry subjects follow `{topic}-value`.

| Schema | Topic | Producer | Consumer |
|--------|-------|----------|----------|
| `transaction-event.avsc` | `transaction-events` | transactions-service outbox | notification-service |
| `payment-event.avsc` | `payment-events.v1` | payments-worker outbox | notification-service |
| `movement-lifecycle-event.avsc` | `movement-lifecycle-v1` | transactions-service outbox | external / future (dedupe by `eventId`) |

## Code generation

Do not hand-edit generated types. Regenerate after changing a `.avsc` file.

**Go** (structs with `avro` tags for `github.com/twmb/avro`):

```bash
./scripts/avro-gen.sh
# or: go generate ./backend/notification-service/internal/events/...
```

Output: [`backend/notification-service/internal/events/events_gen.go`](../../backend/notification-service/internal/events/events_gen.go).

**Java** (Apache Avro SpecificRecords in package `com.banking.events`):

```bash
./gradlew :transactions-service:generateAvroJava :payments-service:generateAvroJava
```

Wired into each service’s `compileJava` (`generateAvroJava` task). Output under `build/generated/avro/java`.

## Runtime

Avro is the default for `transaction-events`, `payment-events.v1`, and `movement-lifecycle-v1`. Defaults are `SCHEMA_REGISTRY_URL=http://banking-kafka:8081` and `KAFKA_VALUE_FORMAT=avro`. Set `KAFKA_VALUE_FORMAT=json` to publish UTF-8 JSON instead. Producers register the generated schema and emit Confluent wire format (`0x00` + schema id + Avro binary). Notification-service always configures Schema Registry (same default URL) and decodes transaction/payment Avro with `github.com/twmb/avro` and `github.com/twmb/franz-go/pkg/sr`; legacy JSON payloads are still accepted when the magic byte is absent.

The outbox may still store JSON (legacy transaction JSON, payment JSON, or movement lifecycle JSON matching the Avro field names); Avro encoding happens at publish time. Relays claim one unpublished row with a short `FOR UPDATE SKIP LOCKED` transaction, publish outside that lock, and mark `published_at` only after the broker ack. Encode or broker failures retry up to `TRANSACTIONS_OUTBOX_MAX_ATTEMPTS`; exhausted rows are quarantined in Postgres (`failed_at`) so the relay can continue. Published and quarantined rows older than `TRANSACTIONS_OUTBOX_RETENTION_DAYS` / `PAYMENTS_OUTBOX_RETENTION_DAYS` (default 14) are deleted. `movement-lifecycle-v1` has no in-repo consumer—external consumers must deduplicate by `eventId`.
