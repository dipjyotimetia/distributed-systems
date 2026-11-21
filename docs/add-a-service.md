# Add a service

Use this when the playground needs another backend. Cards and KYC are Go gRPC services. Their mutual TLS overlay is `docker-compose.mtls.yml`.

## 1. Code and contract

Put the service in `backend/<name>-service`. Register Java services in `settings.gradle.kts` and put each service version in its own `gradle.properties`. Bump that version when the pull request changes service code. Do not edit `frontend/package.json`, `simulation-client/package.json`, or Kubernetes image tags by hand. CI syncs those from `VERSION` on merge to `master`.

Publish the API before the handlers:

- HTTP: an OpenAPI file next to the service, following `backend/accounts-service/openapi.yml` and `backend/transactions-service/openapi.yml`. Run `make openapi-gen` in the same change. That refreshes the Java, C#, and Python server models, the TypeScript types in `frontend/src/gen/http`, and the Go client under `tests/integration/temporal/gen`. Wire the new paths through `contracts/frontend-api.openapi.yml` when the browser or a journey calls them.
- gRPC: one protobuf file under `proto/`. That directory is the only contract. Fraud is `proto/fraud/fraud.proto`, KYC is `proto/kyc/kyc.proto`, and cards is `proto/cards/cards.proto`. `transactions-service` and `payments-service` compile the fraud file, so their image builds use the repository root as the Docker context. The checked-in Go stubs under `backend/fraud-service/gen` come from the same file. The frontend checks in Buf TypeScript under `frontend/src/gen`, produced by `scripts/frontend-proto.sh` (Buf 1.75.0 and `protoc-gen-es` 2.16.0). Regenerate the stubs in the same change as the proto.
- Kafka: document the JSON fields next to the producer. `transaction-events` is written by `TransactionEventOutbox` and read by `notification-service`.

## 2. Identity

Every service except `user-service` checks the caller JWT. Copy the filter style in `backend/accounts-service` or `backend/transactions-service`: read `Authorization: Bearer`, verify the shared HMAC secret, and take the user id from the token. Do not trust a user id in the body.

The Next.js frontend is the browser's API. For an HTTP service, add a route under `frontend/src/app/api` that calls `API_GATEWAY_URL` and forwards `Authorization`, `Idempotency-Key`, and `traceparent`. Then add the gateway route in `GatewayConfig`. KYC and cards routes call the generated gRPC clients in `frontend/src/lib/grpc/banking.ts` and forward `Authorization` as gRPC metadata.

## 3. Data

Compose runs one PostgreSQL database. Each service owns a schema (`user_service`, `accounts_service`, `transactions_service`, `payments`, `disputes`). That shared database is a demo shortcut, not a pattern to copy into production. Put migrations in the service, grant the service user rights on its schema only, and do not query another service's tables.

If a write must be followed by a message or a workflow start, insert an outbox row in the same database transaction. `payments.outbox`, `disputes.workflow_outbox`, and `transactions_service.event_outbox` are the examples. Money movement that a client may retry should store an `Idempotency-Key` and return the original result.

## 4. Local Compose

Add a service to `docker-compose.yml` on `banking-network` with a healthcheck. Point OpenTelemetry at Jaeger (`4317` for gRPC, `4318` for HTTP). Set `OTEL_SERVICE_NAME`. Add a Prometheus scrape in `config/prometheus.yml`.

Default Compose should start without live third-party credentials. `ai-service` uses `AI_PROVIDER_MODE=fixture` for that reason. Optional brokers and workers stay in an overlay, the way Artemis and `payments-service` do.

Gateway fault injection is off unless `FAULT_INJECTION_RATE` is greater than zero.

## 5. Kubernetes and proof

Add a deployment and Service under `kubernetes/base`, then include them from the base kustomization. Reuse the app ConfigMap for OTel. Image tags are updated by CI.

Add a row to `docs/banking-journey-coverage.md` and a test that calls the frontend route. Unit tests stay next to the service. `make test-backend` and `make test-frontend` are the local gates.

## 6. Demo traffic

If the journey should show up on the Banking App dashboard, teach `simulation-client` to call it at a low rate and keep the call optional through `simulation-client/src/config/index.js`. Disputes and statement jobs are on by default. Outbound payments stay behind `PAYMENTS_ENABLED` because the payments stack is an overlay.
