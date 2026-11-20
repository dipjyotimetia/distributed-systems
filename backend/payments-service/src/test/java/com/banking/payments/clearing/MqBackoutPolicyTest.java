package com.banking.payments.clearing;

import jakarta.jms.BytesMessage;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.JMSConsumer;
import jakarta.jms.JMSContext;
import jakarta.jms.JMSProducer;
import jakarta.jms.Queue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MqBackoutPolicyTest {
    private final JMSContext context = mock(JMSContext.class);
    private final BytesMessage message = mock(BytesMessage.class);
    private final Queue backout = mock(Queue.class);
    private final JMSProducer producer = mock(JMSProducer.class);
    private MqClearingTransport transport;

    @BeforeEach
    void deliveredMessage() throws Exception {
        var factory = mock(ConnectionFactory.class);
        var input = mock(Queue.class);
        var consumer = mock(JMSConsumer.class);
        transport = new MqClearingTransport(factory, "synthetic", "synthetic");
        when(factory.createContext("synthetic", "synthetic", JMSContext.SESSION_TRANSACTED)).thenReturn(context);
        when(context.createQueue("input")).thenReturn(input);
        when(context.createQueue("backout")).thenReturn(backout);
        when(context.createConsumer(input)).thenReturn(consumer);
        when(consumer.receive(10)).thenReturn(message);
        when(context.createProducer()).thenReturn(producer);
        when(producer.setDeliveryMode(jakarta.jms.DeliveryMode.PERSISTENT)).thenReturn(producer);
    }

    @Test
    void malformedMessageIsRetriedBeforeTheDeliveryLimit() throws Exception {
        when(message.getBodyLength()).thenReturn(2L);
        when(message.getIntProperty("JMSXDeliveryCount")).thenReturn(2);
        assertThrows(MqClearingTransport.InvalidRecord.class,
                () -> transport.receive("input", "backout", 10, (record, trace) -> { }));
        verify(context).rollback();
        verify(context, never()).commit();
    }

    @Test
    void malformedMessageMovesToBackoutInOneCommit() throws Exception {
        when(message.getBodyLength()).thenReturn(2L);
        when(message.getIntProperty("JMSXDeliveryCount")).thenReturn(3);
        assertThrows(MqClearingTransport.InvalidRecord.class,
                () -> transport.receive("input", "backout", 10, (record, trace) -> { }));
        verify(message).reset();
        verify(producer).send(backout, message);
        verify(context).commit();
    }

    @Test
    void repeatedTransientFailuresNeverQuarantineValidRecords() throws Exception {
        UUID id = UUID.randomUUID();
        byte[] valid = new ClearingRecord(ClearingRecord.Kind.SETTLED, id, 25, "C00000000001").encode();
        when(message.getJMSCorrelationID()).thenReturn(id.toString());
        when(message.getStringProperty("businessDate")).thenReturn("2026-11-20");
        when(message.getBodyLength()).thenReturn((long) valid.length);
        when(message.readBytes(any(byte[].class))).thenAnswer(call -> {
            byte[] destination = call.getArgument(0);
            System.arraycopy(valid, 0, destination, 0, valid.length);
            return valid.length;
        });
        when(message.getIntProperty("JMSXDeliveryCount")).thenReturn(15);
        assertThrows(IllegalStateException.class, () -> transport.receive("input", "backout", 10, (record, trace) -> {
            throw new IllegalStateException("database unavailable");
        }));
        verify(context).rollback();
        verify(context, never()).createProducer();
        verify(context, never()).commit();
    }
}
