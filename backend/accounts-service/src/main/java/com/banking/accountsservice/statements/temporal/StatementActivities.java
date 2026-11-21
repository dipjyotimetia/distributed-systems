package com.banking.accountsservice.statements.temporal;

import com.banking.accountsservice.service.StatementExportService;
import com.banking.accountsservice.statements.generated.v1.StatementExpiry;
import com.banking.accountsservice.statements.generated.v1.StatementGeneration;
import com.banking.accountsservice.statements.generated.v1.StatementJob;
import com.banking.accountsservice.statements.persistence.StatementJobStore;
import com.banking.accountsservice.statements.persistence.StatementJobStore.Work;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.trace.Tracer;
import io.temporal.failure.ApplicationFailure;
import java.util.UUID;
import java.time.Duration;

public class StatementActivities implements StatementExportWorkflow.Activities {
    private final StatementJobStore jobs;
    private final StatementExportService statements;
    private final ObjectMapper mapper;
    private final Tracer tracer;
    private final Duration retention;

    public StatementActivities(StatementJobStore jobs, StatementExportService statements, ObjectMapper mapper, Tracer tracer, Duration retention) {
        this.jobs = jobs;
        this.statements = statements;
        this.mapper = mapper;
        this.tracer = tracer;
        this.retention = retention;
    }

    @Override
    public StatementGeneration generate(StatementJob job) {
        var span = tracer.spanBuilder("statements.generate").startSpan();
        try (var scope = span.makeCurrent()) {
            UUID id = UUID.fromString(job.getJobId());
            Work work = jobs.work(id);
            if ("QUEUED".equals(work.status())) {
                Object statement = work.periodStart() == null
                        ? statements.generateStatement(work.accountId(), work.customerId())
                        : statements.generatePeriod(work.accountId(), work.customerId(), work.periodStart(), work.periodEnd());
                byte[] document = mapper.writeValueAsBytes(statement);
                jobs.ready(id, document, retention);
                work = jobs.work(id);
            }
            return result(work);
        } catch (Exception failure) {
            span.setStatus(io.opentelemetry.api.trace.StatusCode.ERROR);
            // Activity failure details become part of workflow history.
            throw ApplicationFailure.newFailure("Statement generation failed", "StatementGenerationFailed");
        } finally {
            span.end();
        }
    }

    @Override
    public StatementGeneration recoverOrFail(StatementJob job) {
        var span = tracer.spanBuilder("statements.recover").startSpan();
        try (var scope = span.makeCurrent()) {
            UUID id = UUID.fromString(job.getJobId());
            jobs.fail(id);
            return result(jobs.work(id));
        } catch (RuntimeException failure) {
            span.setStatus(io.opentelemetry.api.trace.StatusCode.ERROR);
            throw ApplicationFailure.newFailure("Statement recovery failed", "StatementRecoveryFailed");
        } finally {
            span.end();
        }
    }

    @Override
    public StatementExpiry expire(StatementJob job) {
        var span = tracer.spanBuilder("statements.expire").startSpan();
        try (var scope = span.makeCurrent()) {
            UUID id = UUID.fromString(job.getJobId());
            boolean complete = jobs.expire(id) || !"READY".equals(jobs.work(id).status());
            return StatementExpiry.newBuilder().setComplete(complete).build();
        } catch (RuntimeException failure) {
            span.setStatus(io.opentelemetry.api.trace.StatusCode.ERROR);
            throw ApplicationFailure.newFailure("Statement expiry failed", "StatementExpiryFailed");
        } finally {
            span.end();
        }
    }

    private static StatementGeneration result(Work work) {
        if (!"READY".equals(work.status())) {
            return StatementGeneration.getDefaultInstance();
        }
        long millis = work.expiresAt().plusNanos(999_999).toEpochMilli();
        return StatementGeneration.newBuilder().setExpiresAtEpochMillis(millis).build();
    }
}
