package com.banking.payments;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

@Component
@EnableScheduling
@Profile("!clearing & !payment-worker & !reconciliation")
public class PaymentMetrics {
    private final JdbcTemplate db;
    private final AtomicReference<Snapshot> latest = new AtomicReference<>(new Snapshot(0, 0, 0, 0, 0, 0));

    private record Snapshot(double pendingAge, double workflowAge, double mqAge, double kafkaAge, long discrepancies, double reviewAge) {}

    public PaymentMetrics(JdbcTemplate db, MeterRegistry registry) {
        this.db = db;
        Gauge.builder("payments.oldest.pending.seconds", latest, state -> state.get().pendingAge()).register(registry);
        Gauge.builder("payments.outbox.oldest.seconds", latest, state -> state.get().workflowAge()).tag("destination", "workflow").register(registry);
        Gauge.builder("payments.outbox.oldest.seconds", latest, state -> state.get().mqAge()).tag("destination", "mq").register(registry);
        Gauge.builder("payments.outbox.oldest.seconds", latest, state -> state.get().kafkaAge()).tag("destination", "kafka").register(registry);
        Gauge.builder("payments.oldest.review.seconds", latest, state -> state.get().reviewAge()).register(registry);
        Gauge.builder("payments.reconciliation.discrepancies", latest, state -> state.get().discrepancies()).register(registry);
    }

    @Scheduled(fixedDelay = 15000)
    public void update() {
        latest.set(db.queryForObject("""
                SELECT (
                    SELECT coalesce(extract(epoch FROM (clock_timestamp() - min(created_at))), 0)
                    FROM payments.payment WHERE status IN ('PENDING','SUBMITTED','ACCEPTED','REVIEW_REQUIRED')
                ), coalesce(extract(epoch FROM (clock_timestamp() - min(created_at) FILTER (WHERE destination = 'WORKFLOW'))), 0),
                   coalesce(extract(epoch FROM (clock_timestamp() - min(created_at) FILTER (WHERE destination = 'MQ'))), 0),
                   coalesce(extract(epoch FROM (clock_timestamp() - min(created_at) FILTER (WHERE destination = 'KAFKA'))), 0),
                   (SELECT count(*) FROM payments.reconciliation_result WHERE outcome <> 'MATCHED'),
                   (SELECT coalesce(extract(epoch FROM (clock_timestamp() - min(t.occurred_at))), 0)
                    FROM payments.transition t JOIN payments.payment p ON p.id = t.payment_id
                    WHERE t.status = 'REVIEW_REQUIRED' AND p.status = 'REVIEW_REQUIRED')
                FROM payments.outbox WHERE published_at IS NULL
                """, (row, index) -> new Snapshot(row.getDouble(1), row.getDouble(2), row.getDouble(3), row.getDouble(4), row.getLong(5), row.getDouble(6))));
    }
}
