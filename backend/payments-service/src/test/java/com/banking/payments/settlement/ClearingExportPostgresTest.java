package com.banking.payments.settlement;

import com.banking.payments.clearing.ClearingRecord;
import com.banking.payments.clearing.SettlementFile;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named = "PAYMENTS_TEST_CLEARING_DB_URL", matches = ".+")
class ClearingExportPostgresTest {
    @Test
    void exportsOnlySettlementsBeforeTheCutoffAndRetriesTheSameBytes() {
        var source = new DriverManagerDataSource(System.getenv("PAYMENTS_TEST_CLEARING_DB_URL"),
                System.getenv("PAYMENTS_DB_USER"), System.getenv("PAYMENTS_DB_PASSWORD"));
        Flyway.configure().dataSource(source).schemas("clearing").locations("classpath:db/clearing").load().migrate();
        var db = new JdbcTemplate(source);
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        var calendar = new BusinessDay(ZoneOffset.UTC, LocalTime.of(17, 0));
        var date = calendar.latestClosed(Instant.now());
        assertEquals(0, db.queryForObject("SELECT count(*) FROM clearing.settlement_export", Integer.class),
                "use a fresh disposable clearing database");
        UUID included = UUID.randomUUID();
        UUID nextDay = UUID.randomUUID();
        insert(db, included, "C00000000001", calendar.end(date).minusSeconds(1));
        insert(db, nextDay, "C00000000002", calendar.end(date));

        var objects = mock(SettlementObjects.class);
        doThrow(new IllegalStateException("synthetic storage outage")).doNothing().when(objects).put(eq(date), any());
        var exporter = new SettlementExport(db, transaction, calendar, objects);
        assertThrows(IllegalStateException.class, exporter::export);
        byte[] original = db.queryForObject("""
                SELECT payload FROM clearing.settlement_export WHERE business_date = ? AND uploaded_at IS NULL
                """, byte[].class, date);
        assertNotNull(original);
        SettlementFile file = SettlementFile.decode(original);
        assertEquals(1, file.sequence());
        assertEquals(25, file.totalMinor());
        assertEquals(new ClearingRecord(ClearingRecord.Kind.SETTLED, included, 25, "C00000000001"), file.records().getFirst());
        assertEquals(1, file.records().size());

        exporter.export();
        assertArrayEquals(original, db.queryForObject("""
                SELECT payload FROM clearing.settlement_export WHERE business_date = ? AND uploaded_at IS NOT NULL
                """, byte[].class, date));
        verify(objects, times(2)).put(eq(date), aryEq(original));
    }

    private static void insert(JdbcTemplate db, UUID id, String reference, Instant due) {
        db.update("""
                INSERT INTO clearing.instruction (payment_id, amount_minor, destination, reference)
                VALUES (?, 25, 'DEST00000001', ?)
                """, id, reference);
        db.update("INSERT INTO clearing.reply (payment_id, kind, due_at) VALUES (?, 'SETTLED', ?)",
                id, due.atOffset(ZoneOffset.UTC));
    }
}
