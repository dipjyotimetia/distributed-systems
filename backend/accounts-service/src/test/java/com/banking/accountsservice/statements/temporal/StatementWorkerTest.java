package com.banking.accountsservice.statements.temporal;

import com.banking.accountsservice.service.StatementExportService;
import com.banking.accountsservice.statements.persistence.StatementJobStore;
import com.banking.accountsservice.statements.persistence.StatementJobStore.Work;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.OpenTelemetry;
import io.temporal.testing.TestWorkflowEnvironment;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StatementWorkerTest {
    @Test
    void dispatchesAnOutboxRetryWithTheSameWorkflowId() throws Exception {
        try (var env = TestWorkflowEnvironment.newInstance()) {
            var jobs = mock(StatementJobStore.class);
            var id = UUID.randomUUID();
            when(jobs.pending()).thenThrow(new IllegalStateException("Database unavailable"))
                    .thenReturn(List.of(id));
            when(jobs.work(id)).thenReturn(new Work(42, 7, "FAILED", null, null, null));
            var worker = new StatementWorker(jobs, mock(StatementExportService.class), new ObjectMapper(),
                    OpenTelemetry.noop().getTracer("test"), "unused", 604800);
            assertEquals(Status.DOWN, worker.health().getStatus());
            worker.start(env.getWorkflowServiceStubs());
            try {
                assertEquals(Status.UP, worker.health().getStatus());
                worker.dispatch();
                verify(jobs, never()).acknowledge(id);
                worker.dispatch();
                env.getWorkflowClient().newUntypedWorkflowStub("statement-" + id)
                        .getResult(10, TimeUnit.SECONDS, Void.class);
                worker.dispatch();
                verify(jobs, times(2)).acknowledge(id);
                verify(jobs, times(1)).work(id);
            } finally {
                worker.stop();
            }
        }
    }
}
