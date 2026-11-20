package com.banking.payments;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Component
public class PaymentLifecycle {
    private final JdbcTemplate db;

    public PaymentLifecycle(JdbcTemplate db) {
        this.db = db;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean transition(UUID paymentId, String expected, String next, String reference, String reason) {
        if (expected.equals(next)) return false;
        int changed = db.update("""
                UPDATE payments.payment
                SET status = ?, clearing_reference = coalesce(?, clearing_reference)
                WHERE id = ? AND status = ?
                """, next, reference, paymentId, expected);
        if (changed == 0) {
            return false;
        }
        append(paymentId, next, reason);
        return true;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void append(UUID paymentId, String status, String reason) {
        UUID eventId = UUID.randomUUID();
        db.update("""
                INSERT INTO payments.transition (id, payment_id, status, reason)
                VALUES (?, ?, ?, ?)
                """, eventId, paymentId, status, reason);
        db.update("""
                INSERT INTO payments.outbox (id, payment_id, destination, body)
                SELECT ?, id, 'KAFKA', convert_to(jsonb_build_object(
                    'version', 1, 'eventId', ?::text, 'paymentId', id, 'customerId', customer_id,
                    'amountMinor', amount_minor, 'currency', 'AUD', 'status', status,
                    'clearingReference', clearing_reference, 'settlementBusinessDate', settlement_business_date,
                    'occurredAt', clock_timestamp()
                )::text, 'UTF8')
                FROM payments.payment WHERE id = ?
                """, eventId, eventId.toString(), paymentId);
    }
}
