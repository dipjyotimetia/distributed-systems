package com.banking.payments;

import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

@Component
@Profile("payment-worker")
public class PaymentEventPublisher {
    private final JdbcTemplate db;
    private final String topic;
    private final PaymentTracing tracing;
    private final KafkaProducer<String, byte[]> producer;
    private final Function<byte[], byte[]> encode;

    @Autowired
    public PaymentEventPublisher(JdbcTemplate db, PaymentTracing tracing, SchemaRegistryAvroEncoder encoder,
                                 @Value("${PAYMENTS_KAFKA_BROKERS}") String brokers,
                                 @Value("${PAYMENTS_KAFKA_TOPIC:payment-events.v1}") String topic) {
        this(db, tracing, brokers, topic, encoder::encode);
    }

    PaymentEventPublisher(JdbcTemplate db, PaymentTracing tracing, String brokers, String topic) {
        this(db, tracing, brokers, topic, body -> body);
    }

    PaymentEventPublisher(JdbcTemplate db, PaymentTracing tracing, String brokers, String topic,
                          Function<byte[], byte[]> encode) {
        this.db = db;
        this.tracing = tracing;
        this.topic = topic;
        this.encode = encode;
        this.producer = new KafkaProducer<>(Map.of(
                "bootstrap.servers", brokers,
                "acks", "all",
                "enable.idempotence", true,
                "request.timeout.ms", 4000,
                "delivery.timeout.ms", 6000,
                "max.block.ms", 4000
        ), new StringSerializer(), new ByteArraySerializer());
    }

    private record Event(UUID id, UUID paymentId, byte[] body, String traceParent) {}

    @Scheduled(fixedDelayString = "${PAYMENTS_KAFKA_POLL_INTERVAL:500}")
    @Transactional
    public void publish() {
        var pending = db.query("""
                SELECT o.id, o.payment_id, o.body, p.trace_parent FROM payments.outbox o JOIN payments.payment p ON p.id = o.payment_id
                WHERE o.destination = 'KAFKA' AND o.published_at IS NULL
                AND NOT EXISTS (
                    SELECT 1 FROM payments.outbox earlier
                    WHERE earlier.payment_id = o.payment_id AND earlier.destination = 'KAFKA'
                      AND earlier.published_at IS NULL AND (earlier.created_at, earlier.id) < (o.created_at, o.id)
                )
                ORDER BY o.created_at, o.id LIMIT 1 FOR UPDATE OF o SKIP LOCKED
                """, (row, index) -> new Event(row.getObject("id", UUID.class),
                row.getObject("payment_id", UUID.class), row.getBytes("body"), row.getString("trace_parent")));
        if (pending.isEmpty()) {
            return;
        }
        Event event = pending.getFirst();
        var span = tracing.start("payment.kafka.publish", event.paymentId(), event.traceParent());
        try (var scope = span.makeCurrent()) {
            var record = new ProducerRecord<>(topic, event.paymentId().toString(), encode.apply(event.body()));
            String parent = PaymentTracing.currentParent();
            if (parent != null) record.headers().add("traceparent", parent.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            producer.send(record).get(7, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Kafka publication interrupted", interrupted);
        } catch (ExecutionException | TimeoutException failure) {
            throw new IllegalStateException("Kafka acknowledgement missing", failure);
        } finally {
            span.end();
        }
        db.update("UPDATE payments.outbox SET published_at = clock_timestamp() WHERE id = ?", event.id());
    }

    @PreDestroy
    void close() {
        producer.close(Duration.ofSeconds(5));
    }
}
