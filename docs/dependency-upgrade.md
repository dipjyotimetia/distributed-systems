# Full-stack dependency upgrade

> Historical engineering record. See the [current project guide](../README.md) for supported workflows.

The upgrade starts from `dipjyotimetia/distributed-systems` `main` at `82bd0f6`. Version checks used official package registries, image registries, and maintainer releases. The application uses Java 25 LTS, Go 1.27.4, .NET 10 LTS, Python 3.15.2, and Node.js 26.13.1 LTS. A follow-up pass on 2026-11-22 moved Spring Boot to `4.2.0` / Spring Cloud to `2026.0.0`, Flyway to `12.11.0` (Boot-managed line), OpenAPI Generator to `7.27.0`, Avro to `1.12.2`, AWS SDK to `2.59.7`, NuGet `Newtonsoft.Json`/`Npgsql` patches, Python `watchfiles` to `1.3.0`, and refreshed Go module graphs with `go get -u`.

Redpanda is the Kafka-compatible broker for Compose, Kubernetes, and the integration stack. The Kubernetes DNS name remains `banking-kafka:9092`, with Schema Registry on `banking-kafka:8081`. Event topics default to Confluent Avro (`KAFKA_VALUE_FORMAT=avro`); schemas live under `contracts/avro/`. Producers publish through transactional outboxes (claim, broker ack, then `published_at`; transactions-service also quarantines poison rows). The notification service uses franz-go (plus `twmb/avro` and `franz-go/pkg/sr` for Avro) and commits only after processing a record. MongoDB deduplicates replays using the existing transaction/status unique index. The SASL overlay still accepts PLAIN clients and provisions their bootstrap account through secret references.

## Versions held below latest

| Dependency | Installed | Latest checked | Blocker and upgrade step |
| --- | --- | --- | --- |
| Frontend OpenTelemetry API | 1.9.1 from `~1.9.0` | 1.10.0 | Node trace SDK 2.12.1 requires API below 1.10. Upgrade the SDK, resources, exporter, and API together when a compatible Node SDK is available. |
| Frontend OpenTelemetry resources | 2.12.1 | 3.1.1 | Version 3 requires API 1.10. Upgrade with the Node trace SDK. |
| Frontend OTLP HTTP trace exporter | 0.223.2 | 0.301.2 | The 0.301 line requires API 1.10. Upgrade with the Node trace SDK. |
| Frontend TypeScript | 6.0.3 | 7.0.2 | typescript-eslint 8.73.0 declares TypeScript below 6.1. Upgrade when its parser supports TypeScript 7. |
| Frontend ESLint | 9.39.5 | 10.15.0 | The Next.js preset includes import, React, and JSX accessibility plugins whose peer ranges stop at 9. Upgrade when the preset ships compatible plugin versions. |
| ASP.NET Core JWT bearer provider | 10.0.15 | 11.0.0 | The 11 release targets .NET 11. Upgrade with the service's next eligible .NET LTS runtime. |
| Npgsql EF Core provider | 10.0.4 | 11.0.0 | The 11 line targets .NET 11. Stay on the 10.x line with `net10.0`. |
| Flyway | 12.11.0 | 13.13.0 | Spring Boot 4.2 manages Flyway 12.11. Stay on the Boot-managed major until Boot moves. |
| Artemis Jakarta client | 2.44.0 | 2.57.0 | Compose/K8s broker image is still `2.44.0-alpine` (no newer published tag). Keep the client matched to the broker. |
| OTel Collector Contrib | 0.165.0 | 0.166.0-nightly | No stable `0.166.0` tag yet. |
| Frontend lucide-react | ^1.76.0 | 1.77.0 | Registry advertises 1.77.0 but npm install currently fails with ETARGET; retry when the tarball is available. |
| Maven Corretto builder image | 3.10.0-amazoncorretto-25 | Maven 3.10.1 | The registry had no 3.10.1 Corretto 25 variant. Builds already run Maven 3.10.1 through the wrapper. Update the base tag when the variant is published. |

Third-party runtime images (Compose / Kubernetes) checked against registries on 2026-11-22: PostgreSQL `19.0-alpine`, MongoDB `9.0.8`, Redis `8.10.3-alpine`, Redpanda `v26.2.2`, Temporal CLI/dev `1.10.1`, Temporal `auto-setup` `1.29.7`, Artemis `2.44.0-alpine`, Jaeger `2.23.0`, Prometheus `v3.16.0`, Grafana `13.3.1`, Loki `3.7.10`, Alloy `v1.22.0`, OTel Collector Contrib `0.165.0`, Node Exporter `v1.13.1`, BusyBox `1.38.0`, Alpine `3.24.4`, Go `1.27.4-alpine`, Node `26.13.1`, Python `3.15.2-slim`, curl `8.23.0`, and Amazon Corretto `25-alpine` are on the latest published tags for their pinned lines.

`simulation-client` is Node.js only (the unused `go.mod` was removed). Production npm audits were clear. The Python, NuGet, and final Maven audits reported no known findings. Go scans reported no reachable findings in the services or Temporal harness. Full frontend and simulation audits still report moderate findings in development dependencies, mainly Jest's glob/minimatch chain. Update the test toolchain when it resolves patched releases within supported dependency ranges.

## Stateful upgrade evidence

- PostgreSQL 15 to 18.7 used a logical backup and restore into a fresh database. Account balances matched the movement ledger after restart. The existing volume and PVC mount paths still point at their configured data directories. An existing PostgreSQL 15 data directory requires migration before PostgreSQL 18 can start.
- MongoDB 7.0 to 8.0.38 to 9.0.8 used a separate volume and advanced feature compatibility at each step. The notification document and unique index survived each upgrade and the final restart.
- Redis 7 to 8.10.3 loaded a copied RDB from an isolated instance. The rate-limit hash and expiry survived restart.
- Grafana 10 to 13.3.1 reopened an isolated SQLite volume initialized with the repository provisioning and reported a healthy database after restart.
- Loki's Kubernetes storage is pod-local. The updated configuration uses TSDB schema v13 and receives logs over native OTLP. A synthetic log sent through the updated Collector was queryable in Loki. An OTLP trace ingested by Jaeger 2.23 was queryable through both its v3 API and the legacy trace-by-ID endpoint.

These rehearsals used synthetic data. Schedule any live data migration separately and retain the original data for recovery. The Kafka-to-Redpanda change replaces the Kubernetes broker's pod-local store. Environments that retain Kafka data or consumer offsets elsewhere need a separately planned migration.

## Baseline and verification

The original frontend had 166 passing unit tests. Its coverage command already failed the 30% line, statement, and branch gates. After the upgrade, all 166 tests pass and the three coverage measures improve, but remain below those gates and the repository's 80% target. Several Go packages also started below 80%. Coverage thresholds were kept in place.

The original transactions-service Maven build failed to run its Protobuf compiler on macOS ARM. Its replacement builds with the platform-specific compiler and gRPC plugin. The final Java suites ran 85 tests, including the PostgreSQL-backed accounts tests.

The Go suites, legacy Temporal history replay, Python service tests, frontend lint and type checks, Buf build/lint/breaking checks, binding regeneration, and Compose and Kustomize rendering passed locally. The banking journeys exercised identity, ledger integrity, transfer worker replacement, consumer and broker recovery, synchronous statements, disputed transfers, and asynchronous statement generation and expiry. The Kubernetes resources were rendered locally.
