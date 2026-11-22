# Provider isolation overlay

Deploys the banking stack with first-party [`provider-fixtures`](../../../tests/integration/temporal/cmd/provider-fixtures) mocking all configured third-party HTTP APIs. Dummy keys from the base secret are sufficient; unknown fixture paths return 404 (fail-closed).

```bash
# Build and load/push the fixtures image first, then:
make provider-isolation-deploy
# or
kubectl apply -k kubernetes/overlays/provider-isolation/
```

Image: `ghcr.io/dipjyotimetia/distributed-systems/provider-fixtures:<VERSION>` (see root `VERSION`). Build locally with:

```bash
docker build -f tests/integration/temporal/stack/Dockerfile -t ghcr.io/dipjyotimetia/distributed-systems/provider-fixtures:v$(cat VERSION) .
```
