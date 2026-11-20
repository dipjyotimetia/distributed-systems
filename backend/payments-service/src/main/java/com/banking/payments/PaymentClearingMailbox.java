package com.banking.payments;

import com.banking.payments.clearing.ClearingRecord;
import com.banking.payments.clearing.MqClearingTransport;
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

import java.util.UUID;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@Profile("payment-worker")
public class PaymentClearingMailbox {
    private static final Logger LOG = LoggerFactory.getLogger(PaymentClearingMailbox.class);
    private final JdbcTemplate db;
    private final TransactionTemplate transaction;
    private final PaymentLifecycle lifecycle;
    private final PaymentTracing tracing;
    private final MqClearingTransport transport;
    private final String requestQueue;
    private final String replyQueue;
    private final String backoutQueue;

    public PaymentClearingMailbox(JdbcTemplate db, TransactionTemplate transaction, PaymentLifecycle lifecycle, PaymentTracing tracing,
                                   @Value("${MQ_ADDRESS}") String address,
                                   @Value("${MQ_USERNAME}") String username,
                                   @Value("${MQ_PASSWORD}") String password,
                                   @Value("${MQ_REQUEST_QUEUE:DEV.QUEUE.PAYMENTS.REQUEST}") String requestQueue,
                                   @Value("${MQ_REPLY_QUEUE:DEV.QUEUE.PAYMENTS.REPLY}") String replyQueue,
                             @Value("${MQ_BACKOUT_QUEUE:DEV.QUEUE.PAYMENTS.BACKOUT}") String backoutQueue)
            throws JMSException {
        this.db = db;
        this.transaction = transaction;
        this.lifecycle = lifecycle;
        this.tracing = tracing;
        this.transport = MqClearingTransport.connect(address, username, password);
        this.requestQueue = requestQueue;
        this.replyQueue = replyQueue;
        this.backoutQueue = backoutQueue;
    }

    @Scheduled(fixedDelayString = "${PAYMENTS_MQ_POLL_INTERVAL:500}")
    public void publish() {
        try {
            transaction.executeWithoutResult(status -> publishInstruction());
        } catch (JMSRuntimeException failure) {
            LOG.warn("MQ instruction will be retried");
        }
    }

    @Scheduled(fixedDelayString = "${PAYMENTS_MQ_POLL_INTERVAL:500}")
    public void receive() {
        try {
            transport.receive(replyQueue, backoutQueue, 250, this::recordReply);
        } catch (JMSException | JMSRuntimeException failure) {
            LOG.warn("MQ reply receive will be retried");
        } catch (MqClearingTransport.InvalidRecord invalidReply) {
            LOG.warn("Invalid clearing reply returned to MQ for backout processing");
        }
    }

    private record Pending(UUID outboxId, UUID paymentId, byte[] payload, String traceParent) {}

    private void publishInstruction() {
        var rows = db.query("""
                SELECT o.id, o.payment_id, o.body, p.trace_parent FROM payments.outbox o JOIN payments.payment p ON p.id = o.payment_id
                WHERE o.destination = 'MQ' AND published_at IS NULL ORDER BY o.created_at, o.id
                LIMIT 1 FOR UPDATE OF o SKIP LOCKED
                """, (row, index) -> new Pending(row.getObject("id", UUID.class),
                row.getObject("payment_id", UUID.class), row.getBytes("body"), row.getString("trace_parent")));
        if (rows.isEmpty()) {
            return;
        }
        Pending entry = rows.getFirst();
        ClearingRecord record = ClearingRecord.decode(entry.payload());
        if (!entry.paymentId().equals(record.paymentId()) || record.kind() != ClearingRecord.Kind.INSTRUCTION) {
            throw new IllegalStateException("Corrupt MQ outbox entry");
        }
        var span = tracing.start("payment.mq.publish", entry.paymentId(), entry.traceParent());
        try (var scope = span.makeCurrent()) {
            transport.send(requestQueue, record);
        } catch (JMSException failure) {
            throw new JMSRuntimeException("MQ instruction publication failed", null, failure);
        } finally {
            span.end();
        }
        db.update("UPDATE payments.outbox SET published_at = clock_timestamp() WHERE id = ?", entry.outboxId());
        lifecycle.transition(entry.paymentId(), "PENDING", "SUBMITTED", null, "MQ_ACKNOWLEDGED");
    }

    private void recordReply(ClearingRecord reply, MqClearingTransport.Metadata metadata) {
        if (reply.kind() == ClearingRecord.Kind.INSTRUCTION) {
            throw new MqClearingTransport.InvalidRecord("Expected a clearing reply");
        }
        var span = tracing.start("payment.mq.reply", reply.paymentId(), metadata.traceParent());
        try (var scope = span.makeCurrent()) {
            transaction.executeWithoutResult(status -> {
                if (Boolean.FALSE.equals(db.queryForObject("SELECT EXISTS (SELECT 1 FROM payments.payment WHERE id = ?)",
                        Boolean.class, reply.paymentId()))) {
                    throw new MqClearingTransport.InvalidRecord("Unknown clearing correlation");
                }
                int inserted = db.update("""
                        INSERT INTO payments.clearing_inbox (payment_id, kind, amount_minor, reference, business_date)
                        VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
                        """, reply.paymentId(), reply.kind().name(), reply.amountMinor(), reply.identifier(), metadata.businessDate());
                if (inserted == 0 && reply.kind() == ClearingRecord.Kind.SETTLED) {
                    var previous = db.queryForObject("""
                            SELECT business_date FROM payments.clearing_inbox
                            WHERE payment_id = ? AND kind = 'SETTLED' AND amount_minor = ? AND reference = ?
                            """, java.time.LocalDate.class, reply.paymentId(), reply.amountMinor(), reply.identifier());
                    if (!metadata.businessDate().equals(previous)) {
                        throw new MqClearingTransport.InvalidRecord("Conflicting settlement business date");
                    }
                }
            });
        } finally {
            span.end();
        }
    }
}
