package com.banking.accountsservice.statements.temporal;

import com.banking.accountsservice.statements.generated.v1.StatementExpiry;
import com.banking.accountsservice.statements.generated.v1.StatementGeneration;
import com.banking.accountsservice.statements.generated.v1.StatementJob;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.time.Duration;

@WorkflowInterface
public interface StatementExportWorkflow {
    String QUEUE = "accounts-statement-exports";

    @WorkflowMethod(name = "statementExportV1")
    void run(StatementJob job);

    @ActivityInterface
    interface Activities {
        StatementGeneration generate(StatementJob job);
        StatementGeneration recoverOrFail(StatementJob job);
        StatementExpiry expire(StatementJob job);
    }

    class Implementation implements StatementExportWorkflow {
        private final Activities generator = Workflow.newActivityStub(Activities.class,
                ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(30))
                        .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(5).build()).build());
        private final Activities maintenance = Workflow.newActivityStub(Activities.class,
                ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(15))
                        .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(5).build()).build());

        @Override
        public void run(StatementJob job) {
            StatementGeneration result;
            try {
                result = generator.generate(job);
            } catch (ActivityFailure failure) {
                result = retryMaintenance(() -> maintenance.recoverOrFail(job));
            }
            long expiresAt = result.getExpiresAtEpochMillis();
            if (expiresAt == 0) {
                return;
            }
            long remaining = expiresAt - Workflow.currentTimeMillis();
            if (remaining > 0) {
                Workflow.sleep(Duration.ofMillis(remaining));
            }
            while (!retryMaintenance(() -> maintenance.expire(job)).getComplete()) {
                Workflow.sleep(Duration.ofSeconds(5));
            }
        }

        // Keep recovery and deletion durable through extended database outages.
        private <T> T retryMaintenance(java.util.function.Supplier<T> action) {
            while (true) {
                try {
                    return action.get();
                } catch (ActivityFailure failure) {
                    Workflow.sleep(Duration.ofMinutes(1));
                }
            }
        }
    }
}
