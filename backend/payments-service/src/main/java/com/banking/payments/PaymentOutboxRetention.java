package com.banking.payments;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("payment-worker")
public class PaymentOutboxRetention {
    private static final Logger LOG = LoggerFactory.getLogger(PaymentOutboxRetention.class);

    private final JdbcTemplate db;
    private final int retentionDays;

    public PaymentOutboxRetention(JdbcTemplate db,
                                  @Value("${PAYMENTS_OUTBOX_RETENTION_DAYS:14}") int retentionDays) {
        this.db = db;
        this.retentionDays = Math.max(1, retentionDays);
    }

    @Scheduled(fixedDelayString = "${PAYMENTS_OUTBOX_CLEANUP_DELAY_MS:3600000}")
    public void cleanupPublished() {
        int removed = db.update("""
                DELETE FROM payments.outbox
                WHERE published_at IS NOT NULL
                  AND published_at < clock_timestamp() - CAST(? AS INT) * INTERVAL '1 day'
                """, retentionDays);
        if (removed > 0) {
            LOG.info("Removed {} published payment outbox row(s) older than {} day(s)", removed, retentionDays);
        }
    }
}
