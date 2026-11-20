package com.banking.payments;

import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.WorkerFactory;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

@Component
@Profile("payment-worker")
public class PaymentWorker {
    private static final Logger LOG = LoggerFactory.getLogger(PaymentWorker.class);
    private final JdbcTemplate db;
    private final WorkflowServiceStubs connection;
    private final WorkflowClient temporal;
    private final WorkerFactory factory;

    public PaymentWorker(JdbcTemplate db, PaymentWorkflow.Steps steps,
                         @Value("${TEMPORAL_ADDRESS}") String address) {
        this.db = db;
        connection = WorkflowServiceStubs.newServiceStubs(WorkflowServiceStubsOptions.newBuilder()
                .setTarget(address).setRpcTimeout(Duration.ofSeconds(5)).build());
        temporal = WorkflowClient.newInstance(connection);
        factory = WorkerFactory.newInstance(temporal);
        var worker = factory.newWorker(PaymentWorkflow.QUEUE);
        worker.registerWorkflowImplementationTypes(PaymentWorkflow.Implementation.class);
        worker.registerActivitiesImplementations(steps);
    }

    @PostConstruct
    void start() {
        factory.start();
    }

    @PreDestroy
    void stop() {
        factory.shutdown();
        connection.shutdown();
    }

    private record Start(UUID outboxId, UUID paymentId) {}

    @Scheduled(fixedDelayString = "${PAYMENTS_WORKFLOW_POLL_INTERVAL:1000}")
    public void dispatch() {
        var pending = db.query("""
                SELECT id, payment_id FROM payments.outbox
                WHERE destination = 'WORKFLOW' AND published_at IS NULL ORDER BY created_at, id LIMIT 20
                """, (row, index) -> new Start(row.getObject("id", UUID.class), row.getObject("payment_id", UUID.class)));
        for (Start entry : pending) {
            var workflow = temporal.newWorkflowStub(PaymentWorkflow.class, WorkflowOptions.newBuilder()
                    .setTaskQueue(PaymentWorkflow.QUEUE).setWorkflowId("payment-" + entry.paymentId())
                    .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
                    .build());
            try {
                WorkflowClient.start(workflow::run, entry.paymentId());
            } catch (WorkflowExecutionAlreadyStarted acknowledged) {
                // A previous dispatch may have started the workflow before its database acknowledgement.
            } catch (RuntimeException unavailable) {
                LOG.warn("Payment workflow dispatch will retry ({})", unavailable.getClass().getSimpleName());
                return;
            }
            db.update("UPDATE payments.outbox SET published_at = clock_timestamp() WHERE id = ? AND published_at IS NULL",
                    entry.outboxId());
        }
    }
}
