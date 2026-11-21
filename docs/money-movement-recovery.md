# Money movement recovery

Customer deposit, withdrawal, and transfer forms use `/api/v1/movements`. The browser retains the submitted intent and idempotency key for that account and customer in its tab session. An accepted movement may remain pending while screening or the ledger is unavailable. A posted movement already has durable ledger entries. The Kafka topic `movement-lifecycle-v1` carries Confluent Avro ([`movement-lifecycle-event.avsc`](../contracts/avro/movement-lifecycle-event.avsc)). Transactions-service publishes lifecycle events with a unique `eventId` (outbox `event_key`); there is no in-repo consumer for this topic yet—downstream consumers should deduplicate by `eventId`. Existing transaction and payment topics continue to feed notification-service.

The accounts service journals opening balances, adjustments, internal movements, and outbound debits. The exact balance response includes the database-specific `postingCoverageStart` timestamp. Period statements before that cutoff are rejected. Historical balances form the opening baseline for this journal. Before promoting an upgraded environment, reconcile pending legacy decisions, compare historical transaction amounts to exact movement decisions where available, and investigate any unexplained balance or conversion differences. Keep the legacy rows and clearing evidence for that reconciliation.

In a shared environment, give transactions-service and accounts-service the same scoped `MOVEMENT_EXACT_INTERNAL_SECRET`. Provision a different `MOVEMENT_EXACT_OUTBOUND_SECRET` for accounts-service and any authorized typed outbound caller. Existing payment workers still use their legacy ledger RPC while in-flight operations drain.

Payment operators can inspect reviews under `/operations/payments` and record a decision against a specific evidence ID and revision. A timeout keeps the debit hold active. Late conflicting replies appear in the queue for investigation. A resolution is applied only after the ledger acknowledges the idempotent posting or release.

## Disposable recovery gates

Run `make test-integration-temporal`, `make test-integration-payments`, and `make test-integration-payments-kind`. The Compose payment gate quiesces its own test services, takes paired PostgreSQL backups, restores them into an isolated PostgreSQL container, and compares account balances, reservations, postings, payment state, and financial outbox records. It reconciles settled payments to debits and settlement events, checks review holds, and replays archived financial events into a disposable Kafka topic. Its JSON report under `tests/integration/temporal/results/` records observed duration and losses for the compared rows. The companion legacy audit identifies amount mismatches, completed transactions without exact decisions, and nonzero historical baselines for investigation. The broker and worker interruption scenarios exercise replay and redelivery separately.

For a shared environment, stop new submissions and drain legacy account writers before enabling the posting journal. Quiesce writers before taking coordinated snapshots. Include Temporal, clearing, broker, and notification storage in the environment's backup procedure. After restoring, verify held amounts, ledger postings, outstanding decisions, and logical event IDs before resuming writes. Restore compatible readers and workers first. Retain the newer financial journal and audit records during a rollback.

## Notification retry

The notification service persists a per-channel delivery intent with the event before acknowledging Kafka. HTTP 408, 429, 5xx, and transport failures are retried. Failed attempts that reach their limit or receive a permanent provider response are exposed as `EXHAUSTED` in the `deliveries` fields on MongoDB event documents. The exhaustion metric and log identify the channel and event reference.

Malformed Kafka payloads are written to the MongoDB `events_quarantine` collection (keyed by topic, partition, and offset) before the consumer commits past them. Quarantine failures leave the offset uncommitted so the record is retried.

Operators can inspect those documents using their existing MongoDB access. After checking the provider and confirming that an attempt should be repeated, reset only that channel's exhausted state to `PENDING`, its attempt count to zero, and its due time to the current UTC time. The currently configured SendGrid, Twilio, and Slack requests lack provider-side idempotency. An ambiguous provider acknowledgement can therefore result in duplicate delivery.
