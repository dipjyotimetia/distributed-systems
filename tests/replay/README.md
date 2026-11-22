# Offline WireMock replay fixtures

Committed request suites and WireMock stub mappings used by CI merge gates.

| Service | Path | Command |
|---------|------|---------|
| api-gateway | `services/api-gateway/` | `make replay-gate` |
| user-service | `services/user-service/` | `make replay-validation` |

Harness scripts live in [`scripts/replay/`](../../scripts/replay/). Run outputs go to `backend/<service>/replay-results/` (git-ignored).
