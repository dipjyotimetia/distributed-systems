package com.banking.payments;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

import java.time.Duration;
import java.util.UUID;

@WorkflowInterface
public interface PaymentWorkflow {
    String QUEUE = "outbound-aud-payments";

    @WorkflowMethod
    void run(UUID id);

    @ActivityInterface
    interface Steps {
        boolean advance(UUID id);
    }

    class Implementation implements PaymentWorkflow {
        private final Steps steps = Workflow.newActivityStub(Steps.class, ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofSeconds(30))
                .setRetryOptions(RetryOptions.newBuilder().setMaximumInterval(Duration.ofSeconds(30)).build())
                .build());

        @Override
        public void run(UUID id) {
            for (int attempt = 0; attempt < 300; attempt++) {
                if (steps.advance(id)) {
                    return;
                }
                Workflow.sleep(Duration.ofSeconds(5));
            }
            Workflow.continueAsNew(id);
        }
    }
}
