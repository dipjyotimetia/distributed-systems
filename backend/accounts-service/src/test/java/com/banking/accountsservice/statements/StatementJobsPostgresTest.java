package com.banking.accountsservice.statements;

import com.banking.accountsservice.entity.Account;
import com.banking.accountsservice.generated.StatementDocument;
import com.banking.accountsservice.repository.AccountRepository;
import com.banking.accountsservice.service.StatementExportService;
import com.banking.accountsservice.statements.application.StatementJobs;
import com.banking.accountsservice.statements.generated.v1.StatementJob;
import com.banking.accountsservice.statements.persistence.StatementJobStore;
import com.banking.accountsservice.statements.temporal.StatementActivities;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.OpenTelemetry;
import io.temporal.failure.ApplicationFailure;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named = "STATEMENTS_TEST_DATABASE_URL", matches = ".+")
class StatementJobsPostgresTest {
    @Test
    void retainsCommittedGenerationAcrossRetryAndEnforcesExpiryWithWorkerDown() throws Exception {
        var dataSource = new DriverManagerDataSource(System.getenv("STATEMENTS_TEST_DATABASE_URL"),
                System.getenv().getOrDefault("MICROSVC_POSTGRES_USER", "postgres"),
                System.getenv().getOrDefault("MICROSVC_POSTGRES_PASSWORD", ""));
        Flyway.configure().dataSource(dataSource).schemas("accounts_service")
                .locations("classpath:db/migration").load().migrate();
        var jdbc = new JdbcTemplate(dataSource);
        var stored = spy(new StatementJobStore(jdbc));
        var repo = mock(AccountRepository.class);
        when(repo.findByIdAndUserId(42L, 7L)).thenReturn(Optional.of(new Account()));
        var jobs = new StatementJobs(repo, stored);
        var job = jobs.create(42, 7, "export-once-" + java.util.UUID.randomUUID());
        var key = "matching-" + java.util.UUID.randomUUID();
        assertEquals(jobs.create(42, 7, key).id(), jobs.create(42, 7, key).id());
        assertEquals("QUEUED", jobs.status(42, 7, job.id()).status());
        assertTrue(stored.pending().contains(job.id()));
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> jobs.download(42, 7, job.id())).getStatusCode().value());
        assertEquals(404, assertThrows(ResponseStatusException.class,
                () -> jobs.download(42, 8, job.id())).getStatusCode().value());
        assertEquals(404, assertThrows(ResponseStatusException.class,
                () -> jobs.status(42, 8, job.id())).getStatusCode().value());

        var request = StatementJob.newBuilder().setJobId(job.id().toString()).build();
        var generator = mock(StatementExportService.class);
        when(generator.generateStatement(42L, 7L)).thenReturn(new StatementDocument(
                42L, "100", "CHECKING", BigDecimal.valueOf(19), LocalDateTime.parse("2026-01-01T00:00:00"), 7L));
        var mapper = new ObjectMapper();
        var activities = new StatementActivities(stored, generator, mapper, OpenTelemetry.noop().getTracer("test"), Duration.ofDays(7));
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new IllegalStateException("Lost acknowledgement after commit");
        }).when(stored).ready(eq(job.id()), any(), eq(Duration.ofDays(7)));
        assertThrows(ApplicationFailure.class, () -> activities.generate(request));
        var result = activities.generate(request);
        assertTrue(result.getExpiresAtEpochMillis() > 0);
        verify(generator, times(1)).generateStatement(42L, 7L);
        assertEquals(19, mapper.readTree(jobs.download(42, 7, job.id())).get("balance").asInt());
        assertEquals(result, activities.recoverOrFail(request));

        assertFalse(activities.expire(request).getComplete());
        jdbc.update("UPDATE accounts_service.statement_jobs SET expires_at=clock_timestamp() WHERE id=?", job.id());
        assertEquals("EXPIRED", jobs.status(42, 7, job.id()).status());
        assertEquals(410, assertThrows(ResponseStatusException.class,
                () -> jobs.download(42, 7, job.id())).getStatusCode().value());
        assertTrue(activities.expire(request).getComplete());
        assertTrue(activities.expire(request).getComplete());
        assertNull(jdbc.queryForObject("SELECT document FROM accounts_service.statement_jobs WHERE id=?",
                byte[].class, job.id()));

        var failed = jobs.create(42, 7, "new-" + java.util.UUID.randomUUID());
        activities.recoverOrFail(StatementJob.newBuilder().setJobId(failed.id().toString()).build());
        assertEquals("FAILED", jobs.status(42, 7, failed.id()).status());
    }
}
