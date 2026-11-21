package com.banking.payments;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@Profile("!clearing & !payment-worker & !reconciliation")
@Repository
public class PaymentStore {
    public record Instruction(long accountId, String destination, long amountMinor) {}

    public record Payment(UUID id, Instruction instruction, String status, String clearingReference,
                          OffsetDateTime createdAt, LocalDate settlementBusinessDate) {}

    private final JdbcTemplate db;
    private final PaymentLifecycle lifecycle;

    public PaymentStore(JdbcTemplate db, PaymentLifecycle lifecycle) {
        this.db = db;
        this.lifecycle = lifecycle;
    }

    @Transactional
    public Payment initiate(long customer, String key, Instruction instruction) {
        UUID id = UUID.randomUUID();
        int inserted = db.update("""
                INSERT INTO payments.payment
                    (id, customer_id, idempotency_key, account_id, destination, amount_minor, status, trace_parent)
                VALUES (?, ?, ?, ?, ?, ?, 'PENDING', ?)
                ON CONFLICT (customer_id, idempotency_key) DO NOTHING
                """, id, customer, key, instruction.accountId(), instruction.destination(), instruction.amountMinor(), PaymentTracing.currentParent());
        if (inserted == 1) {
            lifecycle.append(id, "PENDING", "REQUEST_RECEIVED");
            db.update("""
                    INSERT INTO payments.outbox (id, payment_id, destination, body)
                    VALUES (?, ?, 'WORKFLOW', ''::bytea)
                    """, UUID.randomUUID(), id);
        }
        Payment stored = db.queryForObject("""
                SELECT * FROM payments.payment WHERE customer_id = ? AND idempotency_key = ?
                """, PaymentStore::read, customer, key);
        if (!stored.instruction().equals(instruction)) {
            throw new ResponseStatusException(CONFLICT, "Idempotency key was used for a different request");
        }
        return stored;
    }

    public List<Payment> listOwned(long customer) {
        return db.query("""
                SELECT * FROM payments.payment WHERE customer_id = ? ORDER BY created_at DESC LIMIT 50
                """, PaymentStore::read, customer);
    }

    public Payment findOwned(long customer, UUID id) {
        return db.query("SELECT * FROM payments.payment WHERE customer_id = ? AND id = ?",
                PaymentStore::read, customer, id).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND));
    }

    private static Payment read(ResultSet row, int index) throws SQLException {
        return new Payment(row.getObject("id", UUID.class),
                new Instruction(row.getLong("account_id"), row.getString("destination"), row.getLong("amount_minor")),
                row.getString("status"), row.getString("clearing_reference"),
                row.getObject("created_at", OffsetDateTime.class), row.getObject("settlement_business_date", LocalDate.class));
    }
}
