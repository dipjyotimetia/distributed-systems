# Kubernetes deployment

Run these commands from the repository root with the target cluster selected.

| Configuration | Purpose |
|---|---|
| `kubernetes/base` | Application, PostgreSQL, Redis, MongoDB, Redpanda and simulation client |
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
`curl -fsS http://localhost:3000/api/healthz` and exercise sign-in through the
frontend. The legacy test Job submitted by `make test-deployment` waits for
`api-gateway:80`, while the current base exposes `banking-gateway:80`. Use
the frontend smoke check for the current base.
