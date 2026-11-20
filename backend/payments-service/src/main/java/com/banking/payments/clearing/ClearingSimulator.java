package com.banking.payments.clearing;

import com.banking.payments.PaymentTracing;
import com.banking.payments.settlement.BusinessDay;
import java.time.OffsetDateTime;
import com.banking.payments.clearing.ClearingRecord.Kind;
import jakarta.jms.JMSException;
import jakarta.jms.JMSRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.UUID;

@Configuration(proxyBeanMethods = false)
@Profile("clearing")
@EnableScheduling
public class ClearingSimulator {
    private static final Logger LOG = LoggerFactory.getLogger(ClearingSimulator.class);
    private final JdbcTemplate db;
    private final TransactionTemplate transaction;
    private final MqClearingTransport mq;
    private final String requestQueue;
    private final String replyQueue;
    private final String backoutQueue;
    private final Duration settlementDelay;
    private final PaymentTracing tracing;
    private final BusinessDay calendar;

    public ClearingSimulator(JdbcTemplate db, TransactionTemplate transaction, PaymentTracing tracing, BusinessDay calendar,
                             @Value("${MQ_ADDRESS}") String address,
                             @Value("${MQ_USERNAME}") String username,
                             @Value("${MQ_PASSWORD}") String password,
                             @Value("${MQ_REQUEST_QUEUE:DEV.QUEUE.PAYMENTS.REQUEST}") String requestQueue,
                             @Value("${MQ_REPLY_QUEUE:DEV.QUEUE.PAYMENTS.REPLY}") String replyQueue,
                             @Value("${MQ_BACKOUT_QUEUE:DEV.QUEUE.PAYMENTS.BACKOUT}") String backoutQueue,
                             @Value("${CLEARING_SETTLEMENT_DELAY:PT10S}") Duration settlementDelay) throws JMSException {
        if (settlementDelay.isNegative() || settlementDelay.isZero()) {
            throw new IllegalArgumentException("Settlement delay must be positive");
        }
        this.db = db;
        this.tracing = tracing;
        this.calendar = calendar;
        this.transaction = transaction;
        this.mq = MqClearingTransport.connect(address, username, password);
        this.requestQueue = requestQueue;
        this.replyQueue = replyQueue;
        this.backoutQueue = backoutQueue;
        this.settlementDelay = settlementDelay;
    }

    @Scheduled(fixedDelayString = "${CLEARING_POLL_INTERVAL:500}")
    public void poll() {
        try {
            mq.receive(requestQueue, backoutQueue, 250, (instruction, metadata) -> {
                var span = tracing.start("clearing.mq.receive", instruction.paymentId(), metadata.traceParent());
                try (var scope = span.makeCurrent()) {
                    transaction.executeWithoutResult(status -> accept(instruction));
                } finally {
                    span.end();
                }
            });
            transaction.executeWithoutResult(status -> publishReply());
        } catch (JMSException | JMSRuntimeException failure) {
            LOG.warn("Clearing queue operation will be retried");
        } catch (MqClearingTransport.InvalidRecord malformed) {
            LOG.warn("Invalid clearing instruction returned to MQ for backout processing");
        }
    }

    private void accept(ClearingRecord record) {
        db.execute("SELECT pg_advisory_xact_lock(351082216)");
        if (record.kind() != Kind.INSTRUCTION) {
            throw new MqClearingTransport.InvalidRecord("Expected an instruction");
        }
        int inserted = db.update("""
                INSERT INTO clearing.instruction (payment_id, amount_minor, destination, reference, trace_parent)
                VALUES (?, ?, ?, 'C' || lpad(nextval('clearing.reference_seq')::text, 11, '0'), ?)
                ON CONFLICT (payment_id) DO NOTHING
                """, record.paymentId(), record.amountMinor(), record.identifier(), PaymentTracing.currentParent());
        if (inserted == 0) {
            boolean identical = Boolean.TRUE.equals(db.queryForObject("""
                    SELECT amount_minor = ? AND destination = ? FROM clearing.instruction WHERE payment_id = ?
                    """, Boolean.class, record.amountMinor(), record.identifier(), record.paymentId()));
            if (!identical) {
                throw new MqClearingTransport.InvalidRecord("Conflicting clearing instruction");
            }
            return;
        }

        switch (record.identifier()) {
            case "REJECT000001" -> queueReply(record.paymentId(), Kind.REJECTED, 0);
            case "SILENT000001" -> { }
            default -> {
                queueReply(record.paymentId(), Kind.ACCEPTED, 0);
                queueReply(record.paymentId(), Kind.SETTLED, settlementDelay.toMillis());
            }
        }
    }

    private void queueReply(UUID id, Kind kind, long delayMillis) {
        db.update("""
                INSERT INTO clearing.reply (payment_id, kind, due_at)
                VALUES (?, ?, clock_timestamp() + ? * interval '1 millisecond')
                """, id, kind.name(), delayMillis);
    }

    private record PendingReply(ClearingRecord record, String traceParent, OffsetDateTime dueAt) {}

    private void publishReply() {
        var pending = db.query("""
                SELECT r.payment_id, r.kind, i.amount_minor, i.reference, i.trace_parent, r.due_at
                FROM clearing.reply r JOIN clearing.instruction i USING (payment_id)
                WHERE r.published_at IS NULL AND r.due_at <= clock_timestamp()
                  AND (r.kind <> 'SETTLED' OR EXISTS (
                      SELECT 1 FROM clearing.reply accepted WHERE accepted.payment_id = r.payment_id
                      AND accepted.kind = 'ACCEPTED' AND accepted.published_at IS NOT NULL))
                ORDER BY r.due_at, r.payment_id
                LIMIT 1 FOR UPDATE OF r SKIP LOCKED
                """, (row, index) -> new PendingReply(new ClearingRecord(Kind.valueOf(row.getString("kind")),
                row.getObject("payment_id", UUID.class), row.getLong("amount_minor"), row.getString("reference")), row.getString("trace_parent"), row.getObject("due_at", OffsetDateTime.class)));
        if (pending.isEmpty()) {
            return;
        }
        var entry = pending.getFirst();
        ClearingRecord reply = entry.record();
        var span = tracing.start("clearing.mq.reply", reply.paymentId(), entry.traceParent());
        try (var scope = span.makeCurrent()) {
            var date = reply.kind() == Kind.SETTLED
                    ? calendar.latestClosed(entry.dueAt().toInstant()).plusDays(1) : null;
            mq.send(replyQueue, reply, date);
        } catch (JMSException failure) {
            throw new IllegalStateException("Could not publish clearing reply", failure);
        } finally {
            span.end();
        }
        db.update("""
                UPDATE clearing.reply SET published_at = clock_timestamp() WHERE payment_id = ? AND kind = ?
                """, reply.paymentId(), reply.kind().name());
    }
}
