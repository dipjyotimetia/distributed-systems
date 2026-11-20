package com.banking.payments.clearing;

import com.banking.payments.PaymentTracing;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import jakarta.jms.BytesMessage;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.DeliveryMode;
import jakarta.jms.JMSContext;
import jakarta.jms.JMSException;
import jakarta.jms.Message;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.function.BiConsumer;

/** Uses separate local MQ transactions for sends and receives. */
public final class MqClearingTransport {
    private static final int INVALID_DELIVERY_LIMIT = 3;
    private final ConnectionFactory factory;
    private final String username;
    private final String password;

    public MqClearingTransport(ConnectionFactory factory, String username, String password) {
        this.factory = factory;
        this.username = username;
        this.password = password;
    }

    public static MqClearingTransport connect(String address, String username, String password) {
        return new MqClearingTransport(new ActiveMQConnectionFactory(address), username, password);
    }

    public void send(String queue, ClearingRecord record) throws JMSException {
        send(queue, record, null);
    }

    public void send(String queue, ClearingRecord record, LocalDate businessDate) throws JMSException {
        if ((record.kind() == ClearingRecord.Kind.SETTLED) != (businessDate != null)) {
            throw new IllegalArgumentException("Only settlement messages require a clearing business date");
        }
        try (JMSContext context = factory.createContext(username, password, JMSContext.SESSION_TRANSACTED)) {
            BytesMessage message = context.createBytesMessage();
            message.setJMSCorrelationID(record.paymentId().toString());
            String parent = PaymentTracing.currentParent();
            if (parent != null) message.setStringProperty("traceparent", parent);
            if (businessDate != null) message.setStringProperty("businessDate", businessDate.toString());
            message.writeBytes(record.encode());
            context.createProducer().setDeliveryMode(DeliveryMode.PERSISTENT).send(context.createQueue(queue), message);
            context.commit();
        }
    }

    public record Metadata(String traceParent, LocalDate businessDate) {}

    public static final class InvalidRecord extends RuntimeException {
        public InvalidRecord(String message) {
            super(message);
        }

        private InvalidRecord(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * The handler must finish its database transaction before returning.
     * Only permanent validation failures should throw InvalidRecord.
     */
    public boolean receive(String queue, String backoutQueue, long timeoutMillis,
                           BiConsumer<ClearingRecord, Metadata> handler) throws JMSException {
        if (timeoutMillis <= 0 || queue.equals(backoutQueue)) {
            throw new IllegalArgumentException("Invalid MQ receive configuration");
        }
        try (JMSContext context = factory.createContext(username, password, JMSContext.SESSION_TRANSACTED);
             var consumer = context.createConsumer(context.createQueue(queue))) {
            Message message = consumer.receive(timeoutMillis);
            if (message == null) {
                return false;
            }
            try {
                ClearingRecord record = decode(message);
                handler.accept(record, new Metadata(message.getStringProperty("traceparent"), date(record, message)));
            } catch (InvalidRecord invalid) {
                if (message.getIntProperty("JMSXDeliveryCount") >= INVALID_DELIVERY_LIMIT) {
                    if (message instanceof BytesMessage bytes) bytes.reset();
                    context.createProducer().setDeliveryMode(DeliveryMode.PERSISTENT)
                            .send(context.createQueue(backoutQueue), message);
                    context.commit();
                } else {
                    context.rollback();
                }
                throw invalid;
            } catch (RuntimeException retryable) {
                context.rollback();
                throw retryable;
            }
            context.commit();
            return true;
        }
    }

    private static LocalDate date(ClearingRecord record, Message message) throws JMSException {
        String date = message.getStringProperty("businessDate");
        if (record.kind() != ClearingRecord.Kind.SETTLED) {
            if (date != null) throw new InvalidRecord("Unexpected clearing business date");
            return null;
        }
        if (date == null || !date.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
            throw new InvalidRecord("Missing or invalid clearing business date");
        }
        try {
            return LocalDate.parse(date);
        } catch (DateTimeParseException malformed) {
            throw new InvalidRecord("Malformed clearing business date", malformed);
        }
    }

    private static ClearingRecord decode(Message message) throws JMSException {
        if (!(message instanceof BytesMessage bytes) || bytes.getBodyLength() != ClearingRecord.LENGTH) {
            throw new InvalidRecord("Invalid MQ clearing message type or length");
        }
        byte[] payload = new byte[ClearingRecord.LENGTH];
        if (bytes.readBytes(payload) != payload.length) {
            throw new InvalidRecord("Truncated MQ clearing record");
        }
        ClearingRecord record;
        try {
            record = ClearingRecord.decode(payload);
        } catch (IllegalArgumentException malformed) {
            throw new InvalidRecord("Malformed MQ clearing record", malformed);
        }
        if (!record.paymentId().toString().equals(message.getJMSCorrelationID())) {
            throw new InvalidRecord("MQ correlation ID does not match clearing record");
        }
        return record;
    }
}
