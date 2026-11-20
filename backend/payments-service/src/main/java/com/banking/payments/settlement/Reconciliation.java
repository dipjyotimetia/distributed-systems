package com.banking.payments.settlement;

import com.banking.payments.clearing.ClearingRecord;
import com.banking.payments.clearing.SettlementFile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@org.springframework.context.annotation.Profile("!clearing")
@Service
public class Reconciliation {
    private final JdbcTemplate db;

    public Reconciliation(JdbcTemplate db) {
        this.db = db;
    }

    @Transactional
    public boolean run(LocalDate date, byte[] evidence) {
        SettlementFile file = SettlementFile.decode(evidence);
        if (!date.equals(file.businessDate())) throw new IllegalArgumentException("Wrong settlement business date");
        String digest = hash(evidence);
        if (db.update("""
                INSERT INTO payments.reconciliation_run (business_date, file_sequence, sha256, evidence)
                VALUES (?, ?, ?, ?) ON CONFLICT (business_date) DO NOTHING
                """, date, file.sequence(), digest, evidence) == 0) {
            if (!digest.equals(db.queryForObject("""
                    SELECT sha256 FROM payments.reconciliation_run WHERE business_date = ?
                    """, String.class, date))) {
                throw new IllegalStateException("Settlement evidence changed after reconciliation");
            }
            appendMissing(date);
            return false;
        }

        Set<UUID> duplicates = file.records().stream().collect(Collectors.groupingBy(
                ClearingRecord::paymentId, Collectors.counting())).entrySet().stream()
                .filter(entry -> entry.getValue() > 1).map(java.util.Map.Entry::getKey).collect(Collectors.toSet());
        int index = 0;
        for (ClearingRecord record : file.records()) {
            record(date, ++index, record, duplicates.contains(record.paymentId()));
        }
        appendMissing(date);
        return true;
    }

    @Transactional
    public int recordLateDiscrepancies() {
        var dates = db.queryForList("""
                SELECT DISTINCT p.settlement_business_date
                FROM payments.payment p
                JOIN payments.reconciliation_run run ON run.business_date = p.settlement_business_date
                WHERE p.status = 'SETTLED' AND NOT EXISTS (
                    SELECT 1 FROM payments.reconciliation_result r
                    WHERE r.business_date = run.business_date AND r.payment_id = p.id
                )
                ORDER BY p.settlement_business_date
                """, LocalDate.class);
        int added = 0;
        for (LocalDate date : dates) {
            added += appendMissing(date);
        }
        return added;
    }

    private int appendMissing(LocalDate date) {
        // Serialize allocation of operator record numbers with imports and explicit replays.
        db.queryForObject("""
                SELECT business_date FROM payments.reconciliation_run WHERE business_date = ? FOR UPDATE
                """, LocalDate.class, date);
        return db.update("""
                INSERT INTO payments.reconciliation_result
                    (business_date, record_number, payment_id, outcome, expected_amount_minor)
                SELECT ?, row_number() OVER (ORDER BY p.id) + coalesce((
                    SELECT max(record_number) FROM payments.reconciliation_result WHERE business_date = ?
                ), 0), p.id, 'MISSING_RECORD', p.amount_minor
                FROM payments.payment p
                WHERE p.status = 'SETTLED' AND p.settlement_business_date = ?
                  AND NOT EXISTS (
                    SELECT 1 FROM payments.reconciliation_result r
                    WHERE r.business_date = ? AND r.payment_id = p.id
                )
                """, date, date, date, date);
    }

    private void record(LocalDate date, int index, ClearingRecord record, boolean duplicate) {
        UUID id = record.paymentId();
        var known = db.query("""
                SELECT amount_minor, clearing_reference, reserved_at IS NOT NULL, status, settlement_business_date
                FROM payments.payment WHERE id = ? FOR UPDATE
                """, (row, ignored) -> new Evidence(row.getLong(1), row.getString(2), row.getBoolean(3),
                row.getString(4), row.getObject(5, LocalDate.class)), id);
        Evidence payment = known.isEmpty() ? null : known.getFirst();
        String outcome;
        if (duplicate) outcome = "DUPLICATE";
        else if (payment == null) outcome = "MISSING_PAYMENT";
        else if (payment.amount() != record.amountMinor()) outcome = "AMOUNT_MISMATCH";
        else if (payment.reference() != null && !payment.reference().equals(record.identifier())) outcome = "REFERENCE_MISMATCH";
        else if (payment.date() != null && !date.equals(payment.date())) outcome = "BUSINESS_DATE_MISMATCH";
        else if (!payment.reserved() || "REJECTED".equals(payment.status())) outcome = "UNEXPECTED_STATE";
        else outcome = recordSettlement(date, record);
        result(date, index, id, outcome, payment == null ? null : payment.amount(), record.amountMinor(), record.identifier());
    }

    private String recordSettlement(LocalDate date, ClearingRecord record) {
        int inserted = db.update("""
                INSERT INTO payments.clearing_inbox (payment_id, kind, amount_minor, reference, business_date)
                VALUES (?, 'SETTLED', ?, ?, ?) ON CONFLICT DO NOTHING
                """, record.paymentId(), record.amountMinor(), record.identifier(), date);
        if (inserted == 1) return "MATCHED";
        LocalDate existing = db.queryForObject("""
                SELECT business_date FROM payments.clearing_inbox
                WHERE payment_id = ? AND kind = 'SETTLED' AND amount_minor = ? AND reference = ?
                """, LocalDate.class, record.paymentId(), record.amountMinor(), record.identifier());
        return date.equals(existing) ? "MATCHED" : "BUSINESS_DATE_MISMATCH";
    }

    private void result(LocalDate date, int index, UUID id, String outcome, Long expected, Long observed, String reference) {
        db.update("""
                INSERT INTO payments.reconciliation_result
                (business_date, record_number, payment_id, outcome, expected_amount_minor, observed_amount_minor, observed_reference)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, date, index, id, outcome, expected, observed, reference);
    }

    private static String hash(byte[] evidence) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(evidence));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 unavailable", failure);
        }
    }

    private record Evidence(long amount, String reference, boolean reserved, String status, LocalDate date) {}
}
