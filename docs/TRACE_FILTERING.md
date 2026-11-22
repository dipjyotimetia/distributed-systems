# Trace filtering

Compose and Kubernetes both drop noisy spans in the OpenTelemetry Collector
before exporting to Jaeger. The filter rules are kept in sync:

- Compose: [`config/otel-collector.yaml`](../config/otel-collector.yaml)
- Kubernetes: [`kubernetes/observability/otel-collector.yaml`](../kubernetes/observability/otel-collector.yaml)

Both drop spans whose name or HTTP URL attributes match Actuator, and drop the
Spring Security spans listed in the filter (`security filterchain before/after`,
`authorize exchange`, `secured request`).

Compose’s traces pipeline is `otlp → filter → batch → otlp` (to `jaeger:4317`).
Kubernetes uses the same filter on traces and additionally runs logs/metrics
pipelines that are not part of local Compose.

## Verify traces

### Docker Compose

```bash
docker compose up -d jaeger otel-collector
# exercise the app, then:
curl -s http://localhost:16686/api/v3/services
```

Confirm application services appear and that Actuator / Spring Security spans
covered by the filter are absent from recent traces.

### Kubernetes

With the app and observability stack running, forward the frontend and Jaeger
services in separate terminals:

```bash
kubectl port-forward -n banking-app svc/banking-frontend 3000:80
kubectl port-forward -n observability svc/jaeger 16686:16686
```

In the frontend, sign in with a local test user and load an account or the
transaction list. In Jaeger, select the application service and inspect a
recent trace for the request. Check that the trace has the expected service
spans and that Actuator and Spring Security spans covered by the filter
are absent. See the [observability guide](../OBSERVABILITY.md) for export
configuration.
