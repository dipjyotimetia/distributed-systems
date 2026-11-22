# Kafka SASL overlay

This overlay enables SASL authentication on the Redpanda Kafka listener and
configures the transactions and notification clients to use it.

```bash
kubectl apply -k kubernetes/overlays/kafka-sasl
```

The demo uses `SASL_PLAINTEXT` with the `PLAIN` mechanism and non-production
credentials in `banking-kafka-sasl`. Transactions-service builds
`sasl.jaas.config` at runtime from `KAFKA_SASL_USERNAME` /
`KAFKA_SASL_PASSWORD` (Kubernetes does not expand `$(VAR)` inside env values).
Notification-service reads the same env vars directly. Use this overlay for
capture and replay testing of authenticated Kafka traffic, not for production.
It does not configure payments-worker.
