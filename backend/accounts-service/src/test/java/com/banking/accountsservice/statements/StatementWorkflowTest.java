package com.banking.accountsservice.statements;

import com.banking.accountsservice.statements.generated.v1.StatementExpiry;
import com.banking.accountsservice.statements.generated.v1.StatementGeneration;
import com.banking.accountsservice.statements.generated.v1.StatementJob;
import com.banking.accountsservice.statements.temporal.StatementExportWorkflow;
import io.temporal.client.WorkflowOptions;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.TestWorkflowEnvironment;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StatementWorkflowTest {
    @Test
    void runsTheSevenDayExpiryTimerAfterARetry() {
        try (var env = TestWorkflowEnvironment.newInstance()) {
            AtomicInteger attempts = new AtomicInteger();
            AtomicLong generated = new AtomicLong();
            AtomicLong expired = new AtomicLong();
            var activities = new StatementExportWorkflow.Activities() {
                @Override
                public StatementGeneration generate(StatementJob job) {
                    if (attempts.incrementAndGet() == 1) {
                        generated.set(env.currentTimeMillis());
                        throw ApplicationFailure.newFailure("Transient", "Test");
                    }
                    return StatementGeneration.newBuilder()
                            .setExpiresAtEpochMillis(generated.get() + Duration.ofDays(7).toMillis()).build();
                }

                @Override
                public StatementGeneration recoverOrFail(StatementJob job) {
                    throw new AssertionError("A successful retry must avoid terminal recovery");
                }

                @Override
                public StatementExpiry expire(StatementJob job) {
                    expired.set(env.currentTimeMillis());
                    return StatementExpiry.newBuilder().setComplete(true).build();
                }
            };
            run(env, activities);
            assertEquals(2, attempts.get());
            assertTrue(expired.get() >= generated.get() + Duration.ofDays(7).toMillis());
        }
    }

    @Test
    void recordsTerminalFailureAfterFiveAttempts() {
        try (var env = TestWorkflowEnvironment.newInstance()) {
            AtomicInteger attempts = new AtomicInteger();
            AtomicInteger terminal = new AtomicInteger();
            run(env, new StatementExportWorkflow.Activities() {
                @Override
                public StatementGeneration generate(StatementJob job) {
                    attempts.incrementAndGet();
                    throw ApplicationFailure.newFailure("Transient", "Test");
                }

                @Override
                public StatementGeneration recoverOrFail(StatementJob job) {
                    terminal.incrementAndGet();
                    return StatementGeneration.getDefaultInstance();
                }

                @Override
                public StatementExpiry expire(StatementJob job) {
                    throw new AssertionError("A failed job must never schedule expiry");
                }
            });
            assertEquals(5, attempts.get());
            assertEquals(1, terminal.get());
        }
    }

    private static void run(TestWorkflowEnvironment env, StatementExportWorkflow.Activities activities) {
        var worker = env.newWorker(StatementExportWorkflow.QUEUE);
        worker.registerWorkflowImplementationTypes(StatementExportWorkflow.Implementation.class);
        worker.registerActivitiesImplementations(activities);
        env.start();
        var workflow = env.getWorkflowClient().newWorkflowStub(StatementExportWorkflow.class,
                WorkflowOptions.newBuilder().setTaskQueue(StatementExportWorkflow.QUEUE).build());
        workflow.run(StatementJob.newBuilder().setJobId(UUID.randomUUID().toString()).build());
    }
}
