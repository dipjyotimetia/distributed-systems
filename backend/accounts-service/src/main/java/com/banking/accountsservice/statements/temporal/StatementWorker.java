package com.banking.accountsservice.statements.temporal;

import com.banking.accountsservice.service.StatementExportService;
import com.banking.accountsservice.statements.generated.v1.StatementJob;
import com.banking.accountsservice.statements.persistence.StatementJobStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.trace.Tracer;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.api.workflowservice.v1.GetSystemInfoRequest;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.WorkerFactory;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.UUID;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration(proxyBeanMethods = false)
@Profile("statement-worker")
@Lazy(false)
@EnableScheduling
public class StatementWorker implements HealthIndicator {
    private static final Logger LOG = LoggerFactory.getLogger(StatementWorker.class);
    private final StatementJobStore jobs;
    private final StatementActivities activities;
    private final String address;
    private WorkflowServiceStubs service;
    private WorkflowClient client;
    private WorkerFactory factory;

    public StatementWorker(StatementJobStore jobs, StatementExportService statements,
            ObjectMapper mapper, Tracer tracer, @Value("${TEMPORAL_ADDRESS:localhost:7233}") String address,
            @Value("${STATEMENTS_TEST_RETENTION_SECONDS:604800}") long retentionSeconds) {
        this.jobs = jobs;
        if (retentionSeconds < 1 || retentionSeconds > Duration.ofDays(7).toSeconds()) {
            throw new IllegalArgumentException("Statement retention must be between one second and seven days");
        }
        this.activities = new StatementActivities(jobs, statements, mapper, tracer, Duration.ofSeconds(retentionSeconds));
        this.address = address;
    }

    @PostConstruct
    void start() {
        start(WorkflowServiceStubs.newServiceStubs(WorkflowServiceStubsOptions.newBuilder().setTarget(address).build()));
    }

    void start(WorkflowServiceStubs stubs) {
        service = stubs;
        try {
            client = WorkflowClient.newInstance(service);
            factory = WorkerFactory.newInstance(client);
            var worker = factory.newWorker(StatementExportWorkflow.QUEUE);
            worker.registerWorkflowImplementationTypes(StatementExportWorkflow.Implementation.class);
            worker.registerActivitiesImplementations(activities);
            factory.start();
        } catch (RuntimeException failure) {
            stop();
            throw failure;
        }
    }

    @Scheduled(fixedDelay = 1000)
    void dispatch() {
        try {
            for (UUID id : jobs.pending()) {
                var workflow = client.newWorkflowStub(StatementExportWorkflow.class, WorkflowOptions.newBuilder()
                        .setWorkflowId("statement-" + id)
                        .setTaskQueue(StatementExportWorkflow.QUEUE)
                        .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
                        .build());
                try {
                    WorkflowClient.start(workflow::run, StatementJob.newBuilder().setJobId(id.toString()).build());
                } catch (WorkflowExecutionAlreadyStarted alreadyStarted) {
                    // The preceding attempt reached Temporal before its outbox acknowledgement.
                }
                jobs.acknowledge(id);
            }
        } catch (RuntimeException failure) {
            LOG.warn("Statement workflow dispatch will retry ({})", failure.getClass().getSimpleName());
        }
    }

    @Override
    public Health health() {
        try {
            if (factory == null || !factory.isStarted() || factory.isShutdown()) {
                return Health.down().build();
            }
            service.blockingStub().withDeadlineAfter(2, TimeUnit.SECONDS).getSystemInfo(GetSystemInfoRequest.getDefaultInstance());
            return Health.up().build();
        } catch (RuntimeException failure) {
            return Health.down().build();
        }
    }

    @PreDestroy
    void stop() {
        if (factory != null) {
            factory.shutdown();
            factory.awaitTermination(10, TimeUnit.SECONDS);
        }
        if (service != null) {
            service.shutdown();
        }
    }
}
