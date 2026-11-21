package com.banking.transactionsservice.event;

import com.banking.transactionsservice.entity.Transaction;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import transactions.v1.MovementLifecycleEvent;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class TransactionEventOutbox {
    private static final Logger logger = LoggerFactory.getLogger(TransactionEventOutbox.class);

    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final String topic;
    private final boolean publishEnabled;
    private final AtomicReference<Double> oldestSeconds = new AtomicReference<>(0.0);

    public TransactionEventOutbox(JdbcTemplate jdbc, KafkaTemplate<String, String> kafka, MeterRegistry registry,
                                  @Value("${kafka.topic.transactions:transaction-events}") String topic,
                                  @Value("${transactions.outbox.publish-enabled:true}") boolean publishEnabled) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.topic = topic;
        this.publishEnabled = publishEnabled;
        Gauge.builder("transactions.outbox.oldest.seconds", oldestSeconds, AtomicReference::get).register(registry);
    }

    public void enqueue(Transaction transaction) {
        jdbc.update("""
                INSERT INTO transactions_service.event_outbox (transaction_id, payload)
                VALUES (?, ?)
                """, transaction.getId(), payload(transaction));
    }

    public void enqueueMovement(UUID movementId, MovementLifecycleEvent event) {
        try {
            jdbc.update("""
                    INSERT INTO transactions_service.event_outbox (movement_id, topic, payload, event_key)
                    VALUES (?, 'movement-lifecycle-v1', ?, ?)
                    ON CONFLICT (event_key) DO NOTHING
                    """, movementId, JsonFormat.printer().omittingInsignificantWhitespace().print(event), event.getEventId());
        } catch (InvalidProtocolBufferException invalid) {
            throw new IllegalStateException("Unable to serialize movement event", invalid);
        }
    }

    @Scheduled(fixedDelayString = "${transactions.outbox.delay-ms:1000}")
    public void publishPending() {
        if (!publishEnabled) {
            return;
        }
        oldestSeconds.set(jdbc.queryForObject("""
                SELECT COALESCE(EXTRACT(EPOCH FROM (CURRENT_TIMESTAMP - MIN(created_at))), 0)
                FROM transactions_service.event_outbox WHERE published_at IS NULL
                """, Double.class));
        List<Pending> pending = jdbc.query("""
                SELECT e.id, e.transaction_id, e.movement_id, e.topic, e.payload
                FROM transactions_service.event_outbox e
                WHERE e.published_at IS NULL AND NOT EXISTS (
                    SELECT 1 FROM transactions_service.movement_decisions d
                    WHERE d.ledger_id = e.transaction_id AND NOT d.finalized
                )
                ORDER BY e.id LIMIT 20
                """, (rs, row) -> new Pending(rs.getLong("id"),
                rs.getString("transaction_id"), rs.getString("movement_id"), rs.getString("topic"), rs.getString("payload")));
        for (Pending event : pending) {
            try {
                kafka.send(event.topic() == null ? topic : event.topic(),
                        event.movementId() == null ? event.transactionId() : event.movementId(), event.payload()).get(5, TimeUnit.SECONDS);
            } catch (Exception failure) {
                if (failure instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                logger.warn("Transaction event outbox publish stopped at id {}: {}", event.id(), failure.toString());
                return;
            }
            jdbc.update("""
                    UPDATE transactions_service.event_outbox SET published_at = CURRENT_TIMESTAMP
                    WHERE id = ? AND published_at IS NULL
                    """, event.id());
        }
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

    private record Pending(long id, String transactionId, String movementId, String topic, String payload) {}
}
