# Trace filtering

The [Kubernetes collector](../kubernetes/observability/otel-collector.yaml)
drops spans whose name or HTTP URL attributes match Actuator, and drops the
Spring Security spans listed in its filter. Compose exports to Jaeger
directly, so the Kubernetes collector rules do not apply there.

## Verify traces

With the Kubernetes app and observability stack running, forward the
frontend and Jaeger services in separate terminals:

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
