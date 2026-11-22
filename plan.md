# Banking Application Implementation Plan

The completed phases record delivery milestones. See the [README](README.md) for current deployment support and [architecture.md](architecture.md) for the runtime topology.

## Shipped since Phase 9

These capabilities landed after the numbered Phase 9 checklist and are part of the current product:

- **KYC and cards** — Go gRPC services (`:8091` / `:8092`), frontend Buf clients, optional mTLS Compose overlay
- **Disputes** — intake API, Temporal escalation/investigation, operator HITL, transactions query gRPC
- **Avro event pipeline** — Confluent Avro via Schema Registry for `transaction-events`, `payment-events.v1`, `movement-lifecycle-v1`
- **Financial outbox hardening** — `SKIP LOCKED` claim, poison-row quarantine (`failed_at`), published-row retention for transactions and payments Kafka relays
- **Temporal workers** — disputes, statement exports, KYC review, AI banking agent (payments worker on overlay)
- **Internal gRPC mesh** — accounts ledger `:9092`, transactions query `:9095`, dispute research proto
- **Optional payments** — Compose/K8s overlays with Artemis clearing and reconciliation
- **Provider isolation** — `kubernetes/overlays/provider-isolation` with first-party `provider-fixtures` (Speedscale removed)
- **Offline replay** — WireMock request suites under `tests/replay/` for api-gateway and user-service CI gates
- **Knowledge vault** — `knowledge-service` document store + fixture embeddings, AI `search_knowledge` / `get_document_excerpt` tools, thin MCP stdio server

## Phase 1: Project Setup & Infrastructure ✅ COMPLETED
- [x] Create monorepo structure with backend services, frontend, and infrastructure directories
- [x] Set up PostgreSQL with separate schemas for each microservice
- [x] Configure Docker Compose environment with logging and health checks

## Phase 2: Backend Services Development ✅ COMPLETED
- [x] Implement the core identity, accounts, transactions, and gateway services
- [x] Add JWT authentication, database persistence, and initial OpenTelemetry instrumentation
- [x] Create service test suites

## Phase 3: Frontend Development ✅ COMPLETED
- [x] Build Next.js frontend with TypeScript and authentication context
- [x] Implement responsive UI components for all banking operations
- [x] Integrate with backend APIs using comprehensive error handling

## Phase 4: Testing ✅ COMPLETED
- [x] Complete backend testing suite (unit, integration, API tests)
- [x] Frontend testing with Jest, React Testing Library, and Playwright E2E tests
- [ ] Verify the repository’s >80% coverage target across all services
- [x] **Backend Test Fixes**: Resolved JWT configuration issues in accounts-service by removing OAuth2 resource server configuration and using consistent custom JWT filter approach across all services

## Phase 5: Observability & Monitoring ✅ COMPLETED
- [x] Deploy and configure a robust OpenTelemetry instrumentation across all services
- [x] Set up Jaeger, Prometheus, and Grafana monitoring stack
- [x] Configure and verify distributed tracing and custom business metrics

## Phase 6: Containerization ✅ COMPLETED
- [x] Create optimized Docker images with multi-stage builds and security hardening
- [x] Refactor API endpoints for consistent resource-based structure
- [x] Validate containerized environment with comprehensive integration testing

## Phase 7: Kubernetes Development & Testing ✅ COMPLETED
- [x] Create Kubernetes manifests with RBAC, ConfigMaps, and Secrets
- [x] Deploy and debug full stack on minikube with automated scripts
- [x] Build comprehensive E2E test client validating core authentication workflows

## Phase 8: CI/CD Pipeline & Production Readiness ✅ COMPLETED
- [x] Set up GitHub Actions for automated building and testing with multi-architecture Docker images (ARM/AMD)
- [x] Configure Docker image registry with proper tagging, versioning, and optimize Spring Boot startup times
- [x] Simplify API Gateway routing architecture by eliminating complex path rewriting and implementing transparent proxy pattern

## Phase 9: Observability & CI/CD Finalization ✅ COMPLETED
- [x] OpenTelemetry pure implementation (disabled Speedscale dependency)
- [x] Clean trace filtering removing health check noise
- [x] Fixed frontend CI test failures (Jest/Playwright separation)
- [x] Standardized OTel configuration across all services
- [x] E2E testing framework with Playwright
- [x] **Fixed Cross-Application Trace Propagation**: Resolved issues preventing trace IDs from propagating between pods by:
  - [x] Fixed frontend trace context generation using proper W3C Trace Context format
  - [x] Added Spring Cloud Gateway trace propagation configuration with TraceIdInjectionFilter
  - [x] Updated RestTemplate configurations in backend services to use RestTemplateBuilder for automatic trace propagation
  - [x] Standardized OpenTelemetry endpoints across all services (using otel-collector)
  - [x] Created trace propagation filter for API Gateway to ensure proper header forwarding
  - [x] Added comprehensive trace propagation test script to verify end-to-end tracing

## Phase 10: Production Enhancement & Security
- [ ] **Production Kubernetes Deployment**
  - [ ] Deploy to production cluster with SSL/TLS and ingress
  - [ ] Production monitoring, backup, and disaster recovery
- [ ] **Security Hardening**
  - [ ] Service-to-service authentication
  - [ ] Rate limiting and CORS hardening
  - [ ] JWT improvements (refresh tokens, rotation)
  - [ ] Security headers and input validation
- [ ] **Observability Fine-tuning**
  - [ ] Custom business metrics and Grafana dashboards
  - [ ] Performance monitoring and alerting

## Phase 11: Advanced Features & Documentation
- [ ] Enhanced banking features (account types, transaction categories)
- [ ] Performance optimizations (caching, database tuning)
- [ ] API documentation and security audit
- [ ] Load testing and production validation

## Success Criteria

### Development & Testing (Phases 1-9) ✅ COMPLETE
- [x] All services running and communicating properly
- [x] Users can register, login, and manage accounts
- [x] Full observability with tracing and metrics
- [x] All tests passing (unit, integration, E2E)
- [x] Security requirements met (JWT, HTTPS, input validation)
- [x] Kubernetes manifests created and tested on minikube/Colima
- [x] CI/CD pipeline operational with image registry
- [ ] Validate production readiness against the remaining deployment and security work
- [x] **OpenTelemetry tracing fully functional** with pure implementation (no Speedscale dependency)
- [x] **Clean trace filtering** removing health check noise while preserving business transaction visibility

### Production Deployment (Phases 10-11)
- [ ] Application deployed to production Kubernetes with security hardening
- [ ] Production monitoring, performance optimization, and security audit complete
- [ ] Advanced features and load testing validated
