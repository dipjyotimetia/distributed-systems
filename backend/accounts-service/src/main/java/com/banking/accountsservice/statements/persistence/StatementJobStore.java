package com.banking.accountsservice.statements.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class StatementJobStore {
    private static final String JOBS = "accounts_service.statement_jobs";
    private static final String OUTBOX = "accounts_service.statement_job_outbox";
    private static final String VIEW = """
        id, created_at, expires_at, CASE WHEN status='READY' AND expires_at<=clock_timestamp()
            THEN 'EXPIRED' ELSE status END AS status
        """;
    private final JdbcTemplate jdbc;

    public StatementJobStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Job(UUID id, String status, Instant createdAt, Instant expiresAt) {}
    public record Work(long accountId, long customerId, String status, Instant expiresAt,
                       OffsetDateTime periodStart, OffsetDateTime periodEnd) {}

    public Job create(long accountId, long customer, String key) {
        jdbc.update("""
            WITH inserted AS (
                INSERT INTO accounts_service.statement_jobs (id,account_id,customer_id,idempotency_key)
                VALUES (?,?,?,?) ON CONFLICT (account_id,customer_id,idempotency_key) DO NOTHING
                RETURNING id
            )
            INSERT INTO accounts_service.statement_job_outbox (job_id) SELECT id FROM inserted
            """, UUID.randomUUID(), accountId, customer, key);
        return jdbc.queryForObject("SELECT " + VIEW + " FROM " + JOBS
                + " WHERE account_id=? AND customer_id=? AND idempotency_key=?",
                StatementJobStore::view, accountId, customer, key);
    }

    public Job createPeriod(long accountId, long customer, OffsetDateTime start, OffsetDateTime end) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            WITH inserted AS (
                INSERT INTO accounts_service.statement_jobs
                    (id, account_id, customer_id, idempotency_key, period_start, period_end)
                VALUES (?, ?, ?, ?, ?, ?) RETURNING id
            )
            INSERT INTO accounts_service.statement_job_outbox (job_id) SELECT id FROM inserted
            """, id, accountId, customer, id.toString(), start, end);
        return find(accountId, customer, id).orElseThrow();
    }

    public boolean isPeriodJob(long accountId, long customer, UUID id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM accounts_service.statement_jobs
                    WHERE id = ? AND account_id = ? AND customer_id = ? AND period_start IS NOT NULL)
                """, Boolean.class, id, accountId, customer));
    }

    public List<Job> list(long accountId, long customer, long offset) {
        return jdbc.query("SELECT " + VIEW + " FROM " + JOBS
                + " WHERE account_id=? AND customer_id=? AND period_start IS NULL ORDER BY created_at DESC,id DESC LIMIT 21 OFFSET ?",
                StatementJobStore::view, accountId, customer, offset);
    }

    public Optional<Job> find(long accountId, long customer, UUID id) {
        return jdbc.query("SELECT " + VIEW + " FROM " + JOBS + " WHERE account_id=? AND customer_id=? AND id=?",
                StatementJobStore::view, accountId, customer, id).stream().findFirst();
    }

    public Optional<byte[]> download(long accountId, long customer, UUID id) {
        return jdbc.query("SELECT document FROM " + JOBS
                + " WHERE account_id=? AND customer_id=? AND id=? AND status='READY' AND expires_at>clock_timestamp()",
                (row, n) -> row.getBytes(1), accountId, customer, id).stream().findFirst();
    }

    public Work work(UUID id) {
        return jdbc.queryForObject("SELECT account_id,customer_id,status,expires_at,period_start,period_end FROM " + JOBS + " WHERE id=?",
                (row, n) -> new Work(row.getLong(1), row.getLong(2), row.getString(3), instant(row, "expires_at"),
                        row.getObject("period_start", OffsetDateTime.class),
                        row.getObject("period_end", OffsetDateTime.class)), id);
    }

    public void ready(UUID id, byte[] document, Duration retention) {
        jdbc.update("UPDATE " + JOBS + " SET status='READY', document=?, expires_at=clock_timestamp()+?*interval '1 second'"
                + " WHERE id=? AND status='QUEUED'", document, retention.toSeconds(), id);
    }

    public void fail(UUID id) {
        jdbc.update("UPDATE " + JOBS + " SET status='FAILED' WHERE id=? AND status='QUEUED'", id);
    }

    public boolean expire(UUID id) {
        return jdbc.update("UPDATE " + JOBS + " SET status='EXPIRED',document=NULL"
                + " WHERE id=? AND status='READY' AND expires_at<=clock_timestamp()", id) == 1;
    }

    public List<UUID> pending() {
        return jdbc.query("SELECT job_id FROM " + OUTBOX + " ORDER BY created_at,job_id LIMIT 100",
                (row, n) -> row.getObject(1, UUID.class));
    }

    public void acknowledge(UUID id) {
        jdbc.update("DELETE FROM " + OUTBOX + " WHERE job_id=?", id);
    }

    private static Job view(ResultSet row, int index) throws SQLException {
        return new Job(row.getObject("id", UUID.class), row.getString("status"),
                instant(row, "created_at"), instant(row, "expires_at"));
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        OffsetDateTime timestamp = row.getObject(column, OffsetDateTime.class);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
