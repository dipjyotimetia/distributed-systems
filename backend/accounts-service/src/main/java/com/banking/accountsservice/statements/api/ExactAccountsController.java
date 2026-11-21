package com.banking.accountsservice.statements.api;

import com.banking.accountsservice.contract.ContractJson;
import com.banking.accountsservice.contract.ExactAccountsApi;
import com.banking.accountsservice.contract.model.ExactBalances;
import com.banking.accountsservice.contract.model.PeriodStatement;
import com.banking.accountsservice.contract.model.StatementJob;
import com.banking.accountsservice.contract.model.StatementPeriod;
import com.banking.accountsservice.repository.AccountRepository;
import com.banking.accountsservice.security.UserAuthenticationDetails;
import com.banking.accountsservice.service.StatementExportService;
import com.banking.accountsservice.statements.application.StatementJobs;
import com.banking.accountsservice.statements.persistence.StatementJobStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.URI;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@RestController
public class ExactAccountsController implements ExactAccountsApi {
    private final JdbcTemplate jdbc;
    private final AccountRepository accounts;
    private final StatementJobStore store;
    private final StatementJobs jobs;
    private final StatementExportService statements;
    private final ContractJson contractJson;
    private final ObjectMapper mapper;

    public ExactAccountsController(JdbcTemplate jdbc, AccountRepository accounts, StatementJobStore store,
                                   StatementJobs jobs, StatementExportService statements, ContractJson contractJson,
                                   ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.store = store;
        this.jobs = jobs;
        this.statements = statements;
        this.contractJson = contractJson;
        this.mapper = mapper;
    }

    @Override
    public ResponseEntity<ExactBalances> getExactBalances(Long accountId) {
        var balance = jdbc.query("""
                SELECT a.currency, a.balance, h.held, a.balance - h.held AS available,
                       statement_timestamp() AS as_of, c.starts_at
                FROM accounts_service.accounts a CROSS JOIN accounts_service.posting_coverage c
                CROSS JOIN LATERAL (
                    SELECT COALESCE(SUM(amount), 0.00) AS held FROM accounts_service.movement_reservations
                    WHERE from_account_id = a.id AND state = 'RESERVED'
                ) h
                WHERE a.id = ? AND a.user_id = ?
                """, (row, index) -> new ExactBalances(row.getString("currency"),
                row.getBigDecimal("balance").toPlainString(), row.getBigDecimal("held").toPlainString(),
                row.getBigDecimal("available").toPlainString(), row.getObject("as_of", OffsetDateTime.class))
                .postingCoverageStart(row.getObject("starts_at", OffsetDateTime.class)),
                accountId, customer()).stream().findFirst().orElseThrow(ExactAccountsController::notFound);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(balance);
    }

    @Override
    public ResponseEntity<StatementJob> createPeriodStatement(Long accountId, StatementPeriod period) {
        long user = customer();
        if (accounts.findByIdAndUserId(accountId, user).isEmpty()) throw notFound();
        var start = period.getStart();
        var end = period.getEnd();
        if (start == null || end == null || !start.getOffset().equals(ZoneOffset.UTC)
                || !end.getOffset().equals(ZoneOffset.UTC) || !start.isBefore(end)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_PERIOD");
        }
        if (start.isBefore(statements.coverageStart()) || end.isAfter(OffsetDateTime.now(ZoneOffset.UTC))) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "PERIOD_OUTSIDE_POSTING_COVERAGE");
        }
        var job = store.createPeriod(accountId, user, start, end);
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore())
                .location(URI.create("/api/v2/accounts/" + accountId + "/statement-jobs/" + job.id()))
                .body(contractJson.convert(job, StatementJob.class));
    }

    @Override
    public ResponseEntity<StatementJob> getPeriodStatement(Long accountId, UUID jobId) {
        long user = customer();
        if (!store.isPeriodJob(accountId, user, jobId)) throw notFound();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(contractJson.convert(jobs.status(accountId, user, jobId), StatementJob.class));
    }

    @Override
    public ResponseEntity<PeriodStatement> downloadPeriodStatement(Long accountId, UUID jobId) {
        long user = customer();
        if (!store.isPeriodJob(accountId, user, jobId)) throw notFound();
        byte[] document = jobs.download(accountId, user, jobId);
        try {
            return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                    .header("Content-Disposition", "attachment; filename=\"statement-" + jobId + ".json\"")
                    .body(mapper.readValue(document, PeriodStatement.class));
        } catch (IOException corrupted) {
            throw new IllegalStateException("Stored period statement is invalid", corrupted);
        }
    }

    private static long customer() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return ((UserAuthenticationDetails) authentication.getDetails()).getUserId();
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
}
