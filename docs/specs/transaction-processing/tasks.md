# Tasks — Transaction Processing

> Historical specification. See the [current architecture](../../../architecture.md) and [project README](../../../README.md).

Endpoints, fraud screening, ownership and balance rules, locked mutation with compensation,
payment fan-out, Kafka events, and observability are implemented.

- [x] 1. Same-account transfers are rejected in `MovementCoordinator` before a ledger write. _Req 1.2_
- [x] 2. Movement decisions use `REQUIRES_NEW`, and account changes go through `MovementReservations`. _Req 4.3_
- [x] 3. `MovementReservations` serializes balance writes in PostgreSQL. Fraud unavailability follows `fraud.fail-open` (default fail-open, `FRAUD_FAIL_OPEN=false` rejects). _Req 4.1_
- [x] 4. `TransactionEventOutbox` inserts `transaction-events` in the same transaction as the ledger row. _Req 4.2, 4.3_
- [x] 4a. Outbox relay claims with `FOR UPDATE SKIP LOCKED`, quarantines poison rows after max attempts, and deletes published rows after retention. _Req 6_
- [x] 5. `Idempotency-Key` on deposit, withdraw, transfer, and create returns the original transaction. A reused key with a different body returns 409. _Req 1, 4.3_
