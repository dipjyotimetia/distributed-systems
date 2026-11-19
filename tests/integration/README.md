# Banking integration tests

Run the deterministic journeys from the repository root.

```sh
make test-integration-temporal
```

The command requires Docker Compose 2.24.4 or newer, Go 1.26 or newer, and
Python 3. Each scenario gets a fresh Compose project, a synthetic identity,
and a unique Temporal task queue. The harness runs the Temporal workflows,
replays their histories in memory, and writes one JSON report per scenario
under `temporal/results/`. The reports record execution, replay and cleanup
verdicts, and successful boundary assertions.

Use `MICROSVC_BROWSER=1` to run the real-stack Chromium check in the identity
scenario. Install it once with `cd frontend && npm ci && npx playwright install chromium`.
The PR workflow installs Chromium and enables the check. To run just the
extended notification recovery scenario, use
`MICROSVC_SCENARIO=consumer-recovery make test-integration-temporal`. Scheduled
and manual CI runs include this scenario.

## Follow a transfer

1. Run `make test-integration-temporal-ui` and open the exact history URL
   printed for the transfer run.
2. Find the frontend transfer submission in `SubmitTransfer`. The scenario
   starts with USD 100 and USD 20. The ledger records a completed USD 25
   transfer, leaving USD 75 and USD 45.
3. Find the commit checkpoint and resume signal. The replacement worker
   completes the same run. The harness confirms its identity from the
   completion event and replays the history with the Go SDK.
4. Find the broker, database and delivery checks. The Kafka probe reports
   the partition and offset. The MongoDB probe checks the transfer document
   directly. The fixture checks each of Slack, SendGrid and Twilio for one
   matching delivery and records its HTTP status.

The recovery scenario stops the consumer before publishing the transfer,
starts it and waits for its offset to commit, then restarts the broker and
verifies its committed offset. It restarts the consumer and publishes a duplicate. It checks that the document remains unique and that each provider
was called once, including the configured Slack HTTP 503. Delivery is
best-effort if a crash interrupts the fan-out after persistence.

The notification service verifies its MongoDB unique index on startup.
Existing collections with repeated transaction ID and status pairs require
deduplication before rollout.

See the [coverage matrix](../../docs/banking-journey-coverage.md) for the
route-by-route assertions. Run `go test ./...` under `temporal/` for focused
harness tests.

## Account movement recovery

The accounts service holds the affected accounts in ascending ID order and
records the operation ID and original balances. The transactions service
commits its ledger entry and movement decision in one PostgreSQL transaction.
Only a committed decision can settle the reservation. Settlement updates
both balances in one database transaction. Compensation releases the hold
and prevents a late reserve request from reopening it.

The transactions service reconciles unresolved decisions periodically and
before a new movement on the affected accounts. An ambiguous response can
return HTTP 503. A new request creates a new operation, so callers should
check the ledger before resubmitting an ambiguous movement.

Apply both services' migrations before enabling the new movement flow.
Both services must use the same `MOVEMENT_SECRET` if configured. The
existing shared JWT secret supplies the default signing key.

## Disputes and statement worker recovery

Install the frontend Playwright dependencies and Chromium, then run both
browser journeys in a fresh Compose project.

```bash
MICROSVC_SCENARIO=disputes make test-integration-temporal
```

The dispute journey checks evidence collection across worker replacement,
escalation, and resolution without ledger changes. The statement journey
checks queued export recovery, authenticated download, and the synchronous
export. It also verifies that the replacement worker completes expiry and
that the download returns HTTP 410 afterward.
