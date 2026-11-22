package com.banking.transactionsservice.event;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TransactionEventOutboxPublishTest {

    @Mock
    private JdbcTemplate jdbc;
    @Mock
    private PlatformTransactionManager manager;
    @Mock
    private KafkaTemplate<String, byte[]> kafka;
    @Mock
    private SchemaRegistryAvroEncoder encoder;

    private SimpleMeterRegistry metrics;
    private TransactionEventOutbox outbox;

    @BeforeEach
    void setUp() {
        metrics = new SimpleMeterRegistry();
        lenient().when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        outbox = new TransactionEventOutbox(jdbc, manager, kafka, encoder, metrics,
                "transaction-events", true, 5, 14);
        lenient().when(encoder.encode(anyString(), anyString())).thenAnswer(invocation ->
                invocation.getArgument(1, String.class).getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void publishPendingMarksRowAfterSuccessfulSend() throws Exception {
        stubOldestAge(0.0);
        stubClaimSequence(row(11L, "99", null, null, "{\"transaction_id\":99}",
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01", 0));
        when(kafka.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        outbox.publishPending();

        verify(encoder).encode("transaction-events", "{\"transaction_id\":99}");
        ArgumentCaptor<ProducerRecord<String, byte[]>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafka).send(captor.capture());
        ProducerRecord<String, byte[]> record = captor.getValue();
        assertEquals("transaction-events", record.topic());
        assertEquals("99", record.key());
        assertNotNull(record.headers().lastHeader("traceparent"));
        assertEquals("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
                new String(record.headers().lastHeader("traceparent").value(), StandardCharsets.US_ASCII));
        verify(jdbc).update(contains("published_at = CURRENT_TIMESTAMP"), eq(11L));
        verify(jdbc, atLeastOnce()).query(contains("FOR UPDATE OF e SKIP LOCKED"), any(RowMapper.class));
        verify(jdbc, atLeastOnce()).query(contains("LIMIT 1"), any(RowMapper.class));
    }

    @Test
    void publishPendingStopsBatchOnTransientFailureWithoutMarking() throws Exception {
        stubOldestAge(1.0);
        stubClaimSequence(row(1L, "10", null, null, "{\"transaction_id\":10}", null, 0));
        when(kafka.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new TimeoutException("broker unreachable")));

        outbox.publishPending();

        verify(kafka, times(1)).send(any(ProducerRecord.class));
        verify(jdbc).update(contains("publish_attempts = ?"), eq(1), contains("TimeoutException"), eq(1L));
        verify(jdbc, never()).update(contains("published_at = CURRENT_TIMESTAMP"), any(Object.class));
        verify(jdbc, never()).update(contains("failed_at = CURRENT_TIMESTAMP"), any(), any(), any());
    }

    @Test
    void publishPendingQuarantinesAndContinuesAfterMaxAttempts() throws Exception {
        stubOldestAge(0.0);
        stubClaimSequence(
                row(1L, "10", null, null, "{\"transaction_id\":10}", null, 4),
                row(2L, "20", null, null, "{\"transaction_id\":20}", null, 0));
        when(kafka.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new TimeoutException("poison")))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        outbox.publishPending();

        verify(kafka, times(2)).send(any(ProducerRecord.class));
        verify(jdbc).update(contains("failed_at = CURRENT_TIMESTAMP"), eq(5), contains("TimeoutException"), eq(1L));
        verify(jdbc).update(contains("published_at = CURRENT_TIMESTAMP"), eq(2L));
        assertEquals(1.0, metrics.counter("transactions.outbox.quarantined").count());
    }

    @Test
    void publishPendingUsesMovementTopicAndKey() throws Exception {
        stubOldestAge(0.0);
        stubClaimSequence(row(7L, null, "mov-1", "movement-lifecycle-v1", "{\"eventId\":\"e1\"}", null, 0));
        when(kafka.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        outbox.publishPending();

        verify(encoder).encode("movement-lifecycle-v1", "{\"eventId\":\"e1\"}");
        ArgumentCaptor<ProducerRecord<String, byte[]>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafka).send(captor.capture());
        assertEquals("movement-lifecycle-v1", captor.getValue().topic());
        assertEquals("mov-1", captor.getValue().key());
        verify(jdbc).update(contains("published_at = CURRENT_TIMESTAMP"), eq(7L));
    }

    @Test
    void cleanupPublishedDeletesPublishedAndQuarantinedRows() {
        when(jdbc.update(contains("DELETE FROM transactions_service.event_outbox"), eq(14), eq(14)))
                .thenReturn(3);

        outbox.cleanupPublished();

        verify(jdbc).update(argThat((String sql) ->
                        sql.contains("published_at IS NOT NULL") && sql.contains("failed_at IS NOT NULL")),
                eq(14), eq(14));
    }

    @Test
    void cleanupPublishedRunsWhenPublishDisabled() {
        TransactionEventOutbox disabled = new TransactionEventOutbox(jdbc, manager, kafka, encoder, metrics,
                "transaction-events", false, 5, 14);
        when(jdbc.update(contains("DELETE FROM transactions_service.event_outbox"), eq(14), eq(14)))
                .thenReturn(1);

        disabled.cleanupPublished();

        verify(jdbc).update(contains("DELETE FROM transactions_service.event_outbox"), eq(14), eq(14));
        verify(kafka, never()).send(any(ProducerRecord.class));
    }

    private void stubOldestAge(double seconds) {
        when(jdbc.queryForObject(contains("MIN(created_at)"), eq(Double.class))).thenReturn(seconds);
    }

    @SuppressWarnings("unchecked")
    private void stubClaimSequence(ResultSet... rows) throws Exception {
        AtomicInteger index = new AtomicInteger();
        when(jdbc.query(contains("FOR UPDATE OF e SKIP LOCKED"), any(RowMapper.class))).thenAnswer(invocation -> {
            RowMapper<Object> mapper = invocation.getArgument(1);
            int i = index.getAndIncrement();
            if (i >= rows.length) {
                return List.of();
            }
            return List.of(mapper.mapRow(rows[i], 0));
        });
    }

    private static ResultSet row(long id, String transactionId, String movementId, String topic, String payload,
                                 String traceParent, int publishAttempts) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getLong("id")).thenReturn(id);
        when(rs.getString("transaction_id")).thenReturn(transactionId);
        when(rs.getString("movement_id")).thenReturn(movementId);
        when(rs.getString("topic")).thenReturn(topic);
        when(rs.getString("payload")).thenReturn(payload);
        when(rs.getString("trace_parent")).thenReturn(traceParent);
        when(rs.getInt("publish_attempts")).thenReturn(publishAttempts);
        return rs;
    }
}
