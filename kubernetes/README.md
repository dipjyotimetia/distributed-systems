# Kubernetes deployment

Run these commands from the repository root with the target cluster selected.

| Configuration | Purpose |
|---|---|
| `kubernetes/base` | Application, PostgreSQL, Redis, MongoDB, Redpanda (broker + Schema Registry `:8081`) and simulation client |
| `kubernetes/overlays/local` | Base resources plus a frontend NodePort, using the published images |
| `kubernetes/observability` | Collector, Jaeger, Prometheus, Grafana and Loki |
| `kubernetes/overlays/speedscale` | Base and observability resources with six replay responders |
| `kubernetes/overlays/kafka-sasl` | [Redpanda SASL testing](overlays/kafka-sasl/README.md) |

The local and Speedscale overlays omit the disputes service and both Temporal
workers. Use Compose or the [integration stack](../tests/integration/README.md)
for those journeys.

## Local overlay

```bash
kubectl kustomize kubernetes/overlays/local
kubectl apply -k kubernetes/overlays/local
kubectl apply -k kubernetes/observability
kubectl get pods -n banking-app
kubectl port-forward -n banking-app svc/banking-frontend 3000:80
```

Open <http://localhost:3000>. For direct gateway diagnostics, use
`kubectl port-forward -n banking-app svc/banking-gateway 8080:80`.
The observability stack supplies the collector targeted by the application.

For replay, first provision the operator and snapshots described in the
[responder guide](overlays/speedscale/responders/README.md), then apply the
Speedscale overlay. Argo CD runs its PostSync hook during a sync.

## Smoke testing

With the frontend port-forward running, check
`curl -fsS http://localhost:3000/api/v1/healthz` and exercise sign-in through the
frontend. The legacy test Job submitted by `make test-deployment` waits for
`api-gateway:80`, while the current base exposes `banking-gateway:80`. Use
the frontend smoke check for the current base.


## Full banking overlay

`kubernetes/overlays/full` adds the disputes API and worker, the statement
worker, and Temporal backed by the existing PostgreSQL volume. Create the
namespace and provision the `banking-workflow-credentials` Secret before
applying the overlay. Use the same generated disputes password in the
`disputes-password` field and the `disputes-database-url` field. URL-encode
the password in the DSN.

| Secret key | Value |
| --- | --- |
| `temporal-password` | Password for the PostgreSQL `temporal` role |
| `disputes-password` | Password for the PostgreSQL `disputes_service_user` role |
| `disputes-database-url` | `postgres://disputes_service_user:<encoded-password>@banking-postgres:5432/banking_app?sslmode=disable` for local clusters |

The bootstrap Job creates the roles and disputes schema. Existing roles
keep their passwords, so credential rotation requires updating PostgreSQL
as well as the Secret. An optional `banking-workflow-config` ConfigMap key
`disputes-operator-ids` configures the comma-separated operator allowlist.

```bash
kubectl apply -k kubernetes/overlays/full
kubectl -n banking-app wait --for=condition=complete job/banking-workflow-bootstrap-v3 --timeout=5m
kubectl -n banking-app port-forward svc/banking-frontend 3000:80
```

The [journey overlay](testing/journeys/) runs the same workloads with
checkout-built images and synthetic provider fixtures. See the
[shared journey matrix](../docs/banking-journey-coverage.md#shared-deployment-checks)
for the assertions.

## Hybrid outbound payments

Use `overlays/payments-local` on an amd64 or ARM64 development cluster. It adds ActiveMQ Artemis with a persistent broker volume and separate persistent payment
and clearing databases. The application entry point is the frontend. The
MQ listener and PostgreSQL services remain cluster-internal.

Use `overlays/payments-shared` with a PostgreSQL-backed payments database and
an independent clearing database. Provision the `payments` and `clearing`
schemas with their respective application owners. Set `MQ_ADDRESS` to an Artemis CORE URL such as `tcp://broker:61616`.
The broker can run inside the cluster or externally. Use
`config/artemis/broker.xml` for the durable queue definitions. The
application quarantines invalid records on the third delivery. Configure
the TLS endpoint and JVM trust settings for a shared broker.

Provision the full overlay’s workflow credentials and the following
Secrets in the `banking-app` namespace.

| Secret | Required keys |
| --- | --- |
| `banking-payments-db` | `PAYMENTS_DB_URL`, `PAYMENTS_DB_USER`, `PAYMENTS_DB_PASSWORD` |
| `banking-clearing-db` | `CLEARING_DB_URL`, `CLEARING_DB_USER`, `CLEARING_DB_PASSWORD` |
| `banking-payment-movement` | `MOVEMENT_SECRET` shared by accounts, transactions, and the payment worker |
| `banking-payments-worker` | `PAYMENTS_COMPLIANCE_URL`, `PAYMENTS_COMPLIANCE_TOKEN` |
| `banking-payments-mq`, `banking-clearing-mq` | `MQ_PASSWORD`. For shared deployments also supply `MQ_ADDRESS` and `MQ_USERNAME` |
| `banking-settlement-writer`, `banking-settlement-reader` | `SETTLEMENT_BUCKET`, storage credentials or runtime credential configuration, and `SETTLEMENT_S3_ENDPOINT` for an S3-compatible endpoint |

Use `jdbc:postgresql://banking-payments-postgres:5432/payments` and
`jdbc:postgresql://banking-clearing-postgres:5432/clearing` for the local
database URLs. The local overlay initializes both schemas through Flyway.
Set the cutoff and time zone consistently in `banking-payments-config`.
Set `PAYMENTS_OPERATOR_IDS` there to grant read-only access to reconciliation
discrepancies. Provision the `payment-events.v1` Redpanda topic before using
the worker. App config defaults `SCHEMA_REGISTRY_URL=http://banking-kafka:8081`
and `KAFKA_VALUE_FORMAT=avro` for Confluent Avro on `transaction-events`,
`payment-events.v1`, and `movement-lifecycle-v1` ([Avro contracts](../contracts/avro/README.md)).

```bash
kubectl apply -k kubernetes/overlays/payments-local
kubectl -n banking-app port-forward svc/banking-frontend 3000:80
```
