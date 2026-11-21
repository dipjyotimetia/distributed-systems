package com.banking.transactionsservice.event;

import com.banking.transactionsservice.entity.Transaction;
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
                SELECT id, transaction_id, payload FROM transactions_service.event_outbox
                WHERE published_at IS NULL ORDER BY id LIMIT 20
                """, (rs, row) -> new Pending(rs.getLong("id"), rs.getLong("transaction_id"), rs.getString("payload")));
        for (Pending event : pending) {
            try {
                kafka.send(topic, String.valueOf(event.transactionId()), event.payload()).get(5, TimeUnit.SECONDS);
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
                + ",\"amount_minor\":" + transaction.getAmountMinor()
                + ",\"transaction_type\":\"" + transaction.getType().name()
                + "\",\"status\":\"" + transaction.getStatus().name()
                + "\",\"timestamp\":\"" + timestamp + "\"}";
    }

    private record Pending(long id, long transactionId, String payload) {}
}
