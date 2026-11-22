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

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TransactionEventOutboxPublishTest {

    @Mock
    private JdbcTemplate jdbc;
    @Mock
    private KafkaTemplate<String, byte[]> kafka;
    @Mock
    private SchemaRegistryAvroEncoder encoder;

    private TransactionEventOutbox outbox;

    @BeforeEach
    void setUp() {
        outbox = new TransactionEventOutbox(jdbc, kafka, encoder, new SimpleMeterRegistry(),
                "transaction-events", true);
        when(encoder.encode(anyString(), anyString())).thenAnswer(invocation ->
                invocation.getArgument(1, String.class).getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void publishPendingMarksRowAfterSuccessfulSend() throws Exception {
        stubOldestAge(0.0);
        stubPendingRows(row(11L, "99", null, null, "{\"transaction_id\":99}",
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"));
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
    }

    @Test
    void publishPendingStopsBatchOnSendFailureWithoutMarking() throws Exception {
        stubOldestAge(1.0);
        stubPendingRows(
                row(1L, "10", null, null, "{\"transaction_id\":10}", null),
                row(2L, "20", null, null, "{\"transaction_id\":20}", null));
        when(kafka.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new TimeoutException("broker unreachable")));

        outbox.publishPending();

        verify(kafka, times(1)).send(any(ProducerRecord.class));
        verify(jdbc, never()).update(contains("published_at = CURRENT_TIMESTAMP"), any(Object.class));
    }

    @Test
    void publishPendingUsesMovementTopicAndKey() throws Exception {
        stubOldestAge(0.0);
        stubPendingRows(row(7L, null, "mov-1", "movement-lifecycle-v1", "{\"eventId\":\"e1\"}", null));
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

    private void stubOldestAge(double seconds) {
        when(jdbc.queryForObject(contains("MIN(created_at)"), eq(Double.class))).thenReturn(seconds);
    }

    @SuppressWarnings("unchecked")
    private void stubPendingRows(ResultSet... rows) throws Exception {
        when(jdbc.query(contains("published_at IS NULL"), any(RowMapper.class))).thenAnswer(invocation -> {
            RowMapper<Object> mapper = invocation.getArgument(1);
            List<Object> mapped = new ArrayList<>();
            for (ResultSet rs : rows) {
                mapped.add(mapper.mapRow(rs, mapped.size()));
            }
            return mapped;
        });
    }

    private static ResultSet row(long id, String transactionId, String movementId, String topic, String payload,
                                 String traceParent) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getLong("id")).thenReturn(id);
        when(rs.getString("transaction_id")).thenReturn(transactionId);
        when(rs.getString("movement_id")).thenReturn(movementId);
        when(rs.getString("topic")).thenReturn(topic);
        when(rs.getString("payload")).thenReturn(payload);
        when(rs.getString("trace_parent")).thenReturn(traceParent);
        return rs;
    }
}
