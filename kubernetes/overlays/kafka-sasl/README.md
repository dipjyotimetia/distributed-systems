# Kafka SASL overlay

This overlay enables SASL authentication on the Redpanda Kafka listener and
configures the transactions and notification clients to use it.

```bash
kubectl apply -k kubernetes/overlays/kafka-sasl
```

The demo uses `SASL_PLAINTEXT` with the `PLAIN` mechanism and non-production
credentials in `banking-kafka-sasl`. Use it for capture and replay testing of
authenticated Kafka traffic, not for production.
