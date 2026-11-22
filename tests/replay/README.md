# Offline WireMock replay fixtures

Committed request suites and WireMock stub mappings used by the CI `replay-gate` matrix.

| Service | Path | Command |
|---------|------|---------|
| api-gateway | `services/api-gateway/` | `make replay-gate` |
| user-service | `services/user-service/` | `make replay-validation` or `make replay-gate SERVICE=user-service` |
| accounts-service | `services/accounts-service/` | `make replay-gate SERVICE=accounts-service` |
| transactions-service | `services/transactions-service/` | `make replay-gate SERVICE=transactions-service` |
| notification-service | `services/notification-service/` | `make replay-gate SERVICE=notification-service` |
| ai-service | `services/ai-service/` | `make replay-gate SERVICE=ai-service` |

Per-service harness config lives in [`scripts/replay/services/<service>.env`](../../scripts/replay/services/). Shared tools and the entrypoint are under [`scripts/replay/`](../../scripts/replay/).

Run outputs go to `backend/<service>/replay-results/` (git-ignored).
