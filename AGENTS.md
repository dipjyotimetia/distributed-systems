# AGENTS.md

This file is the **single** place for AI coding assistants and other automated agents working in this repository. It is intentionally tool-agnostic (no vendor-specific duplicate instruction files).

## Essential Reading

Before working on this codebase, read:

- `README.md` — Project overview, architecture, and development commands
- `plan.md` — Implementation plan with phases and testing criteria for each sub-phase
- `architecture.md` — System architecture and design decisions

## Repository Scope

Keep application services, deployment assets, and automated application tests in this repository. Checked-in traffic fixtures must be consumed by an automated application test; keep generated recordings and replay results ignored.

Internal demo orchestration, customer rehearsals, cloud-account-specific runners, standalone traffic-analysis tools, and demo-only agent profiles belong in a separate project or private repository. Do not add planted application defects or alternate response contracts for those demonstrations.

## Development Workflow

Delivery milestones live in `plan.md`. Phases **1–9 are complete**. Remaining open work is **Phase 10** (production K8s / security hardening) and **Phase 11** (advanced features). The live service inventory is in `README.md` and `architecture.md` — do not treat the historical phase checklists as the feature list.

Current application backends: `api-gateway`, `user-service`, `accounts-service`, `transactions-service`, `fraud-service`, `kyc-service`, `cards-service`, `disputes-service`, `notification-service`, `ai-service` (+ workers), `knowledge-service` (document vault + RAG/MCP), and optional `payments-service` on overlays.

## Key Commands Reference

**Start development environment**: `docker compose up -d`  
**Build service**: `./gradlew :accounts-service:bootJar` (from repository root)
**Run tests**: `./gradlew test` (backend), `uv sync && uv run pytest` (Python services), or `npm test` (frontend)
**Database migrations**: `./gradlew :accounts-service:flywayMigrate`

## Implementation Guidelines

- Always implement testing criteria for each sub-phase before proceeding
- All services except user-service must implement JWT validation middleware
- Use OpenTelemetry instrumentation in all Java services
- Maintain >80% test coverage for all services
- Follow atomic transaction patterns in transactions-service
- Store JWT tokens in HttpOnly cookies on frontend

## Version Bump Policy

The project uses a two-tier versioning model:

**Global version** — stored in the root `VERSION` file (e.g. `1.4.1`). This is the canonical source of truth for Docker image tags and all non-Java package versions.

**Per-service version** — each Java backend service tracks its own `serviceVersion` in `backend/<service>/gradle.properties`.

### Rules for agents and developers

| File | Who bumps it? | When? |
|------|--------------|-------|
| `backend/<service>/gradle.properties` | Agent / developer | Required in any PR that modifies code under `backend/<service>/` |
| `VERSION` | Agent / developer | When a global release version change is needed (e.g. a minor or major bump) |
| `frontend/package.json` | **CI only — do not touch** | Auto-synced from `VERSION` on every merge to `master` |
| `simulation-client/package.json` | **CI only — do not touch** | Auto-synced from `VERSION` on every merge to `master` |
| `kubernetes/base/deployments/*.yaml` | **CI only — do not touch** | Auto-updated from `VERSION` on every merge to `master` |

### How CI handles versioning (on merge to `master`)

The `update-k8s-manifests` CI job runs `make update-frontend-version` followed by `make update-k8s-version` and commits any resulting changes back to the branch. This is implemented via:

- `scripts/version.sh update-frontend` — syncs `frontend/package.json` and `simulation-client/package.json` to `VERSION`
- `scripts/version.sh update-k8s` — updates image tags in `kubernetes/base/deployments/`

**Do NOT manually bump `frontend/package.json` or `simulation-client/package.json`.** Doing so creates merge conflicts across concurrent branches because multiple agents will all bump from the same base version independently. Let CI handle it.

Docs-only or infra-only changes can skip a `gradle.properties` version bump. CI is the final enforcement gate.

## Current Status

Phases 1–9 are complete. Use `plan.md` for remaining Phase 10–11 production work and the “Shipped since Phase 9” inventory. Use `README.md` / `architecture.md` / `docs/architecture/` for the current runtime topology.
