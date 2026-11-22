package com.banking.transactionsservice.event;

import com.banking.transactionsservice.entity.Transaction;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class TransactionEventOutbox {
    private static final Logger logger = LoggerFactory.getLogger(TransactionEventOutbox.class);
    private static final W3CTraceContextPropagator W3C = W3CTraceContextPropagator.getInstance();
    private static final int ERROR_MAX_LEN = 1000;
    private static final int BATCH_LIMIT = 20;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final KafkaTemplate<String, byte[]> kafka;
    private final SchemaRegistryAvroEncoder encoder;
    private final String topic;
    private final boolean publishEnabled;
    private final int maxAttempts;
    private final int retentionDays;
    private final AtomicReference<Double> oldestSeconds = new AtomicReference<>(0.0);
    private final Counter quarantined;

    public TransactionEventOutbox(JdbcTemplate jdbc, PlatformTransactionManager manager,
                                  KafkaTemplate<String, byte[]> kafka,
                                  SchemaRegistryAvroEncoder encoder, MeterRegistry registry,
                                  @Value("${kafka.topic.transactions:transaction-events}") String topic,
                                  @Value("${transactions.outbox.publish-enabled:true}") boolean publishEnabled,
                                  @Value("${transactions.outbox.max-attempts:5}") int maxAttempts,
                                  @Value("${transactions.outbox.retention-days:14}") int retentionDays) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(manager);
        this.kafka = kafka;
        this.encoder = encoder;
        this.topic = topic;
        this.publishEnabled = publishEnabled;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.retentionDays = Math.max(1, retentionDays);
        Gauge.builder("transactions.outbox.oldest.seconds", oldestSeconds, AtomicReference::get).register(registry);
        this.quarantined = Counter.builder("transactions.outbox.quarantined").register(registry);
    }

    public void enqueue(Transaction transaction) {
        jdbc.update("""
                INSERT INTO transactions_service.event_outbox (transaction_id, payload, trace_parent)
                VALUES (?, ?, ?)
                """, transaction.getId(), payload(transaction), currentTraceParent());
    }

    public void enqueueMovement(UUID movementId, String eventId, String payloadJson) {
        jdbc.update("""
                INSERT INTO transactions_service.event_outbox (movement_id, topic, payload, event_key, trace_parent)
                VALUES (?, 'movement-lifecycle-v1', ?, ?, ?)
                ON CONFLICT (event_key) DO NOTHING
                """, movementId, payloadJson, eventId, currentTraceParent());
    }

    @Scheduled(fixedDelayString = "${transactions.outbox.delay-ms:1000}")
    public void publishPending() {
        if (!publishEnabled) {
            return;
        }
        oldestSeconds.set(jdbc.queryForObject("""
                SELECT COALESCE(EXTRACT(EPOCH FROM (CURRENT_TIMESTAMP - MIN(created_at))), 0)
                FROM transactions_service.event_outbox
                WHERE published_at IS NULL AND failed_at IS NULL
                """, Double.class));
        for (int i = 0; i < BATCH_LIMIT; i++) {
            Pending event = claimNext();
            if (event == null) {
                return;
            }
            String destination = event.topic() == null ? topic : event.topic();
            try {
                byte[] value = encoder.encode(destination, event.payload());
                String key = event.movementId() == null ? event.transactionId() : event.movementId();
                ProducerRecord<String, byte[]> record = new ProducerRecord<>(destination, key, value);
                if (event.traceParent() != null && !event.traceParent().isBlank()) {
                    record.headers().add("traceparent", event.traceParent().getBytes(StandardCharsets.US_ASCII));
                }
                kafka.send(record).get(5, TimeUnit.SECONDS);
            } catch (Exception failure) {
                if (failure instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                boolean quarantinedRow = recordFailure(event, failure);
                if (!quarantinedRow) {
                    logger.warn("Transaction event outbox publish stopped at id {}: {}",
                            event.id(), failure.toString());
                    return;
                }
                continue;
            }
            markPublished(event.id());
        }
    }

    @Scheduled(fixedDelayString = "${transactions.outbox.cleanup-delay-ms:3600000}")
    public void cleanupPublished() {
        int removed = jdbc.update("""
                DELETE FROM transactions_service.event_outbox
                WHERE (published_at IS NOT NULL
                       AND published_at < CURRENT_TIMESTAMP - CAST(? AS INT) * INTERVAL '1 day')
                   OR (failed_at IS NOT NULL
                       AND failed_at < CURRENT_TIMESTAMP - CAST(? AS INT) * INTERVAL '1 day')
                """, retentionDays, retentionDays);
        if (removed > 0) {
            logger.info("Removed {} settled transaction outbox row(s) older than {} day(s)",
                    removed, retentionDays);
        }
    }

    private Pending claimNext() {
        return transaction.execute(status -> {
            List<Pending> pending = jdbc.query("""
                    SELECT e.id, e.transaction_id, e.movement_id, e.topic, e.payload, e.trace_parent,
                           e.publish_attempts
                    FROM transactions_service.event_outbox e
                    WHERE e.published_at IS NULL AND e.failed_at IS NULL AND NOT EXISTS (
                        SELECT 1 FROM transactions_service.movement_decisions d
                        WHERE d.ledger_id = e.transaction_id AND NOT d.finalized
                    )
                    ORDER BY e.id LIMIT 1
                    FOR UPDATE OF e SKIP LOCKED
                    """, (rs, row) -> new Pending(rs.getLong("id"),
                    rs.getString("transaction_id"), rs.getString("movement_id"), rs.getString("topic"),
                    rs.getString("payload"), rs.getString("trace_parent"), rs.getInt("publish_attempts")));
            return pending.isEmpty() ? null : pending.getFirst();
        });
    }

    private void markPublished(long id) {
        transaction.executeWithoutResult(status -> jdbc.update("""
                UPDATE transactions_service.event_outbox SET published_at = CURRENT_TIMESTAMP
                WHERE id = ? AND published_at IS NULL
                """, id));
    }

    /** @return true when the row was quarantined */
    private boolean recordFailure(Pending event, Exception failure) {
        int attempts = event.publishAttempts() + 1;
        String error = truncate(failure.toString());
        return Boolean.TRUE.equals(transaction.execute(status -> {
            if (attempts >= maxAttempts) {
                jdbc.update("""
                        UPDATE transactions_service.event_outbox
                        SET publish_attempts = ?, last_error = ?, failed_at = CURRENT_TIMESTAMP
                        WHERE id = ? AND published_at IS NULL AND failed_at IS NULL
                        """, attempts, error, event.id());
                quarantined.increment();
                logger.error("Transaction event outbox quarantined id {} after {} attempt(s): {}",
                        event.id(), attempts, error);
                return true;
            }
            jdbc.update("""
                    UPDATE transactions_service.event_outbox
                    SET publish_attempts = ?, last_error = ?
                    WHERE id = ? AND published_at IS NULL AND failed_at IS NULL
                    """, attempts, error, event.id());
            return false;
        }));
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= ERROR_MAX_LEN ? value : value.substring(0, ERROR_MAX_LEN);
    }

    static String payload(Transaction transaction) {
        String accountId = transaction.getFromAccountId() != null
                ? transaction.getFromAccountId().toString()
                : transaction.getToAccountId() != null ? transaction.getToAccountId().toString() : "null";
        String timestamp = (transaction.getProcessedAt() != null ? transaction.getProcessedAt() : transaction.getCreatedAt())
                + "Z";
        return "{\"transaction_id\":" + transaction.getId()
                + ",\"account_id\":" + accountId
                + ",\"user_id\":" + transaction.getUserId()
                + ",\"amount\":" + transaction.getAmount().toPlainString()
                + ",\"transaction_type\":\"" + transaction.getType().name()
                + "\",\"status\":\"" + transaction.getStatus().name()
                + "\",\"timestamp\":\"" + timestamp + "\"}";
    }

    static String currentTraceParent() {
        AtomicReference<String> value = new AtomicReference<>();
        W3C.inject(Context.current(), value, (carrier, key, header) -> {
            if ("traceparent".equals(key)) {
                carrier.set(header);
            }
        });
        return value.get();
    }

    private record Pending(long id, String transactionId, String movementId, String topic, String payload,
                           String traceParent, int publishAttempts) {}
}
