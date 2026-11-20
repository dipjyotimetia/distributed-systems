package com.banking.payments;

import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.WorkerFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "PAYMENTS_TEST_TEMPORAL_ADDRESS", matches = ".+")
class PaymentTemporalTest {
    @Test
    void pendingWorkflowContinuesOnReplacementWorkerWithTheSameId() throws Exception {
        String address = System.getenv("PAYMENTS_TEST_TEMPORAL_ADDRESS");
        var services = WorkflowServiceStubs.newServiceStubs(WorkflowServiceStubsOptions.newBuilder()
                .setTarget(address).build());
        var client = WorkflowClient.newInstance(services);
        var first = WorkerFactory.newInstance(client);
        var second = WorkerFactory.newInstance(client);
        UUID id = UUID.randomUUID();
        String queue = "payment-replacement-test-" + id;
        var firstObservation = new CountDownLatch(1);
        var secondObservation = new CountDownLatch(1);

        try {
            configure(first, queue, payment -> {
                assertEquals(id, payment);
                firstObservation.countDown();
                return false;
            });
            var options = WorkflowOptions.newBuilder().setTaskQueue(queue).setWorkflowId("payment-" + id)
                    .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE).build();
            var workflow = client.newWorkflowStub(PaymentWorkflow.class, options);
            WorkflowClient.start(workflow::run, id);
            assertTrue(firstObservation.await(20, TimeUnit.SECONDS));

            first.shutdown();
            first.awaitTermination(10, TimeUnit.SECONDS);
            var duplicate = client.newWorkflowStub(PaymentWorkflow.class, options);
            assertThrows(WorkflowExecutionAlreadyStarted.class, () -> WorkflowClient.start(duplicate::run, id));

            configure(second, queue, payment -> {
                assertEquals(id, payment);
                secondObservation.countDown();
                return true;
            });
            assertTrue(secondObservation.await(20, TimeUnit.SECONDS));
            WorkflowStub.fromTyped(workflow).getResult(20, TimeUnit.SECONDS, Void.class);
        } finally {
            first.shutdownNow();
            second.shutdownNow();
            services.shutdownNow();
        }
    }

    private static void configure(WorkerFactory factory, String queue, PaymentWorkflow.Steps steps) {
        var worker = factory.newWorker(queue);
        worker.registerWorkflowImplementationTypes(PaymentWorkflow.Implementation.class);
        worker.registerActivitiesImplementations(steps);
        factory.start();
    }
}
