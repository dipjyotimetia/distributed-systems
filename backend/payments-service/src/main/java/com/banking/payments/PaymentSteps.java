package com.banking.payments;

import com.banking.payments.clearing.ClearingRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;

@Component
@Profile("payment-worker")
public class PaymentSteps implements PaymentWorkflow.Steps {
    private final JdbcTemplate db;
    private final TransactionTemplate transaction;
    private final PaymentLifecycle lifecycle;
    private final PaymentChecks checks;
    private final Duration replyTimeout;
    private final PaymentTracing tracing;

    public PaymentSteps(JdbcTemplate db, TransactionTemplate transaction, PaymentLifecycle lifecycle,
                        PaymentChecks checks, PaymentTracing tracing, @Value("${PAYMENTS_REPLY_TIMEOUT:PT2M}") Duration replyTimeout) {
        if (replyTimeout.isNegative() || replyTimeout.isZero()) {
            throw new IllegalArgumentException("Clearing reply timeout must be positive");
        }
        this.db = db;
        this.transaction = transaction;
        this.lifecycle = lifecycle;
        this.checks = checks;
        this.replyTimeout = replyTimeout;
        this.tracing = tracing;
    }

    private record Payment(String status, long customer, long account, long cents, String destination,
                           String reference, boolean screened, boolean reserved, boolean expired) {}
    private record Reply(String kind, long cents, String reference, LocalDate date, boolean reconciled) {}

    @Override
    public boolean advance(UUID id) {
        String parent = db.queryForObject("SELECT trace_parent FROM payments.payment WHERE id = ?", String.class, id);
        var span = tracing.start("payment.temporal.advance", id, parent);
        try (var scope = span.makeCurrent()) {
            return Boolean.TRUE.equals(transaction.execute(tx -> process(id)));
        } catch (RuntimeException failure) {
            span.setStatus(io.opentelemetry.api.trace.StatusCode.ERROR);
            throw failure;
        } finally {
            span.end();
        }
    }

    private boolean process(UUID id) {
        Payment p = db.queryForObject("""
                SELECT *, coalesce(clock_timestamp() >= reserved_at + ? * interval '1 millisecond', false) AS expired
                FROM payments.payment WHERE id = ? FOR UPDATE
                """, (row, index) -> new Payment(row.getString("status"), row.getLong("customer_id"),
                row.getLong("account_id"), row.getLong("amount_minor"), row.getString("destination"),
                row.getString("clearing_reference"), row.getObject("screened_at") != null,
                row.getObject("reserved_at") != null, row.getBoolean("expired")), replyTimeout.toMillis(), id);
        if ("SETTLED".equals(p.status()) || "REJECTED".equals(p.status())) return true;

        if (!p.screened()) {
            if (!checks.approved(p.customer(), p.account(), p.cents(), p.destination())) {
                lifecycle.transition(id, p.status(), "REJECTED", null, "SCREENING_DENIED");
                return true;
            }
            db.update("UPDATE payments.payment SET screened_at = clock_timestamp() WHERE id = ?", id);
            return false;
        }
        if (!p.reserved()) {
            if (!checks.move(id, PaymentChecks.Movement.RESERVE, p.customer(), p.account(), p.cents())) {
                lifecycle.transition(id, p.status(), "REJECTED", null, "RESERVATION_DENIED");
                return true;
            }
            db.update("UPDATE payments.payment SET reserved_at = clock_timestamp() WHERE id = ?", id);
            byte[] record = new ClearingRecord(ClearingRecord.Kind.INSTRUCTION, id, p.cents(), p.destination()).encode();
            db.update("""
                    INSERT INTO payments.outbox (id, payment_id, destination, body) VALUES (?, ?, 'MQ', ?)
                    """, UUID.randomUUID(), id, record);
            return false;
        }
        var resolution = db.query("""
                SELECT i.kind, i.amount_minor, i.reference, i.business_date
                FROM payments.review_resolution r
                JOIN payments.clearing_inbox i ON i.evidence_id = r.evidence_id
                WHERE r.payment_id = ? AND r.executed_at IS NULL
                """, (row, index) -> new Reply(row.getString("kind"), row.getLong("amount_minor"),
                row.getString("reference"), row.getObject("business_date", LocalDate.class), false), id);
        if (!resolution.isEmpty()) {
            complete(id, p, resolution.getFirst(), "OPERATOR_RESOLUTION");
            db.update("""
                    UPDATE payments.payment p SET late_exception_at = COALESCE(p.late_exception_at, clock_timestamp())
                    WHERE p.id = ? AND EXISTS (
                        SELECT 1 FROM payments.review_resolution r
                        JOIN payments.clearing_inbox i ON i.payment_id = r.payment_id
                        WHERE r.payment_id = p.id AND i.received_at >= r.created_at AND (
                            (i.kind IN ('SETTLED', 'REJECTED') AND i.kind <> p.status)
                            OR i.amount_minor <> p.amount_minor OR i.reference <> p.clearing_reference
                        )
                    )
                    """, id);
            db.update("UPDATE payments.review_resolution SET executed_at = clock_timestamp() WHERE payment_id = ?", id);
            return true;
        }
        return processReplies(id, p);
    }

    private boolean processReplies(UUID id, Payment p) {
        var replies = db.query("""
                SELECT i.kind, i.amount_minor, i.reference, i.business_date,
                    i.kind = 'SETTLED' AND EXISTS (
                        SELECT 1 FROM payments.reconciliation_result r
                        WHERE r.business_date = i.business_date AND r.payment_id = i.payment_id
                          AND r.outcome = 'MATCHED' AND r.observed_amount_minor = i.amount_minor
                          AND r.observed_reference = i.reference
                    ) AS reconciled
                FROM payments.clearing_inbox i WHERE i.payment_id = ?
                ORDER BY i.received_at, i.kind
                """, (row, index) -> new Reply(row.getString("kind"), row.getLong("amount_minor"),
                row.getString("reference"), row.getObject("business_date", LocalDate.class), row.getBoolean("reconciled")), id);
        var valid = replies.stream().filter(reply -> reply.cents() == p.cents()
                && (p.reference() == null || p.reference().equals(reply.reference()))).toList();
        var reconciled = valid.stream().filter(Reply::reconciled).toList();
        if (reconciled.size() == 1) {
            return complete(id, p, reconciled.getFirst(), "RECONCILED_SETTLEMENT");
        }
        boolean contradictory = reconciled.size() > 1 || valid.stream().map(Reply::reference).distinct().count() > 1
                || (valid.stream().anyMatch(reply -> reply.kind().equals("REJECTED"))
                && valid.stream().anyMatch(reply -> !reply.kind().equals("REJECTED")));
        if (contradictory || valid.size() != replies.size()) {
            lifecycle.transition(id, p.status(), "REVIEW_REQUIRED", null, "CLEARING_EVIDENCE_CONFLICT");
            return false;
        }
        for (Reply reply : valid) {
            if (reply.kind().equals("ACCEPTED")) continue;
            return complete(id, p, reply, "CLEARING_" + reply.kind());
        }
        if (p.expired()) {
            lifecycle.transition(id, p.status(), "REVIEW_REQUIRED", null, "CLEARING_TIMEOUT");
        } else if (!valid.isEmpty() && !"ACCEPTED".equals(p.status()) && !"REVIEW_REQUIRED".equals(p.status())) {
            lifecycle.transition(id, p.status(), "ACCEPTED", valid.getFirst().reference(), "CLEARING_ACCEPTED");
        }
        return false;
    }

    private boolean complete(UUID id, Payment payment, Reply reply, String reason) {
        boolean settled = reply.kind().equals("SETTLED");
        if (!checks.move(id, settled ? PaymentChecks.Movement.COMMIT : PaymentChecks.Movement.COMPENSATE,
                payment.customer(), payment.account(), payment.cents())) {
            throw new IllegalStateException("Account action not acknowledged");
        }
        if (settled) {
            db.update("UPDATE payments.payment SET settlement_business_date = ? WHERE id = ?", reply.date(), id);
        }
        lifecycle.transition(id, payment.status(), reply.kind(), reply.reference(), reason);
        return true;
    }

}
