# System Architecture & Traffic Flow

## Local Development with Colima

The `kubernetes/overlays/local/` overlay uses the same base manifests and namespace (`banking-app`) as the default stack. It does not override container images; services use the published images from the base manifests (e.g. `ghcr.io/dipjyotimetia/distributed-systems/...`).

To run against images built locally (e.g. `localhost:5000/...`), build and tag images, run a local registry if needed, then patch deployments or use `kubectl set image` to point at your registry.

```bash
# Example: deploy the local overlay (same images as base unless you patch)
kubectl apply -k kubernetes/overlays/local/

# Optional: local registry for custom tags
docker run -d -p 5000:5000 --name registry registry:2
docker tag ghcr.io/dipjyotimetia/distributed-systems/frontend:v1.3.4 localhost:5000/frontend:v1.3.4
docker push localhost:5000/frontend:v1.3.4
```

## Traffic Flow Design
Browser banking requests flow through the frontend server. The AI chat proxy uses `AI_SERVICE_URL` to call AI service directly.

```
Client → Frontend Service → API Gateway → Backend Services
```

## Application services and infrastructure

See the [deployment matrix](README.md#quick-start) for which services each environment runs.

The eight application backend services are `api-gateway`, `user-service`,
`accounts-service`, `transactions-service`, `fraud-service`,
`notification-service`, `disputes-service`, and `ai-service`. MongoDB provides infrastructure
for notification event storage. Kafka carries transaction events and
PostgreSQL stores identity, account, transaction, and dispute data.

The disputes API verifies transfer eligibility through the transaction
read endpoint using the caller's JWT. PostgreSQL stores the case and
workflow start together. Evidence requests, responses, and resolutions
update the case and its signal outbox atomically. The investigation workflow
carries only the case ID and revision, and reloads deadlines and status
through an activity. The legacy escalation workflow stays registered,
and both escalation paths guard against changing resolved cases.

Accounts-service stores statement jobs and workflow starts together in
PostgreSQL. A separate Java process runs export workflows on a dedicated task
queue. Activity results carry the expiry timestamp, while the generated
JSON stays in PostgreSQL. Downloads check ownership and expiry on each call.
Local Compose shares one Temporal development server with SQLite on a named
volume across both workers.

## Architecture Principles

- **Frontend as Proxy**: Frontend contains Next.js API routes that proxy requests to the API Gateway
- **Server-Side Communication**: Backend communication uses internal Kubernetes service URLs (e.g., `http://api-gateway:8080`)
- **Relative Client URLs**: Frontend client code uses relative URLs (`/api/users/login`) that route to Next.js API handlers
- **Environment Agnostic**: No hardcoded URLs in client-side code, eliminating `NEXT_PUBLIC_API_URL` configuration issues

## Benefits

- Simplified client configuration (no environment-specific URLs)
- Proper separation of concerns between client and server
- Security through single entry point
- Consistent request/response handling and logging

## Observability

Compose sends traces directly to Jaeger. Kubernetes exports through the
collector, which filters spans before sending traces to Jaeger. The frontend
uses OTLP over HTTP/protobuf. Java services use OTLP over gRPC in the supplied
deployment configurations. See [OTel trace data processing](OBSERVABILITY.md#opentelemetry-trace-data-processing)
for the pipeline and signal-specific configuration.

## Versioning and repository workflow

[AGENTS.md](AGENTS.md#version-bump-policy) defines release versioning and
repository conventions. [plan.md](plan.md) records delivery milestones and
remaining production work.
