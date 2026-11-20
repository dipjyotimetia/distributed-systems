package com.banking.payments.settlement;

import com.banking.payments.clearing.ClearingRecord;
import com.banking.payments.clearing.SettlementFile;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Component
@Profile("clearing")
public class SettlementExport {
    private final JdbcTemplate db;
    private final TransactionTemplate transaction;
    private final BusinessDay calendar;
    private final SettlementObjects objects;

    public SettlementExport(JdbcTemplate db, TransactionTemplate transaction, BusinessDay calendar, SettlementObjects objects) {
        this.db = db;
        this.transaction = transaction;
        this.calendar = calendar;
        this.objects = objects;
    }

    @Scheduled(fixedDelayString = "${CLEARING_EXPORT_INTERVAL:30000}")
    public void export() {
        transaction.executeWithoutResult(status -> prepare());
        transaction.executeWithoutResult(status -> upload());
    }

    private void prepare() {
        // The simulator takes this lock before assigning due_at to a settlement.
        db.execute("SELECT pg_advisory_xact_lock(351082216)");
        Instant now = db.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class).toInstant();
        LocalDate latest = calendar.latestClosed(now);
        LocalDate last = db.queryForObject("SELECT max(business_date) FROM clearing.settlement_export", LocalDate.class);
        LocalDate date = last == null ? firstDay(latest) : last.plusDays(1);
        if (date.isAfter(latest)) return;

        var records = db.query("""
                SELECT i.payment_id, i.amount_minor, i.reference
                FROM clearing.reply r JOIN clearing.instruction i USING (payment_id)
                WHERE r.kind = 'SETTLED' AND r.due_at >= ? AND r.due_at < ?
                ORDER BY r.due_at, i.payment_id LIMIT ?
                """, (row, index) -> new ClearingRecord(ClearingRecord.Kind.SETTLED, row.getObject("payment_id", UUID.class),
                row.getLong("amount_minor"), row.getString("reference")), at(calendar.end(date.minusDays(1))),
                at(calendar.end(date)), SettlementFile.MAX_RECORDS + 1);
        int sequence = db.queryForObject("SELECT nextval('clearing.settlement_sequence')", Integer.class);
        var file = new SettlementFile(date, sequence, records);
        db.update("INSERT INTO clearing.settlement_export(business_date, sequence, payload) VALUES (?, ?, ?)",
                date, sequence, file.encode());
    }

    private LocalDate firstDay(LocalDate latest) {
        OffsetDateTime earliest = db.queryForObject("SELECT min(due_at) FROM clearing.reply WHERE kind = 'SETTLED'",
                OffsetDateTime.class);
        if (earliest == null) return latest;
        LocalDate oldest = calendar.latestClosed(earliest.toInstant()).plusDays(1);
        return oldest.isBefore(latest) ? oldest : latest;
    }

    private void upload() {
        var pending = db.query("""
                SELECT business_date, payload FROM clearing.settlement_export WHERE uploaded_at IS NULL
                ORDER BY business_date LIMIT 1 FOR UPDATE SKIP LOCKED
                """, (row, index) -> new Export(row.getObject("business_date", LocalDate.class), row.getBytes("payload")));
        if (pending.isEmpty()) return;
        Export export = pending.getFirst();
        objects.put(export.date(), export.payload());
        db.update("UPDATE clearing.settlement_export SET uploaded_at = clock_timestamp() WHERE business_date = ?", export.date());
    }

    private static OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private record Export(LocalDate date, byte[] payload) {}
}
