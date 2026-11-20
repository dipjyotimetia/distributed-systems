package com.banking.payments.clearing;

import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import jakarta.jms.DeliveryMode;
import jakarta.jms.JMSContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "PAYMENTS_TEST_MQ_ADDRESS", matches = ".+")
class MqClearingRecoveryTest {
    private static final String REQUEST = "DEV.QUEUE.PAYMENTS.REQUEST";
    private static final String REPLY = "DEV.QUEUE.PAYMENTS.REPLY";
    private static final String BACKOUT = "DEV.QUEUE.PAYMENTS.BACKOUT";

    @Test
    void repeatedInstructionsAndInvalidRepliesReachTheirExpectedQueues() throws Exception {
        String address = System.getenv("PAYMENTS_TEST_MQ_ADDRESS");
        String password = System.getenv("BANKING_MQ_APP_PASSWORD");
        var mq = MqClearingTransport.connect(address, "app", password);
        UUID id = UUID.randomUUID();
        var request = new ClearingRecord(ClearingRecord.Kind.INSTRUCTION, id, 123,
                "REJECT000001");

        try (JMSContext context = raw(address, password);
             var browser = context.createBrowser(context.createQueue(BACKOUT))) {
            int before = depth(browser);
            mq.send(REQUEST, request);
            mq.send(REQUEST, request);

            var evidence = new ObjectMapper().readTree(Path.of(System.getenv("MICROSVC_PAYMENT_EVIDENCE")).toFile());
            var priorSettlement = new ClearingRecord(ClearingRecord.Kind.SETTLED,
                    UUID.fromString(evidence.required("paymentId").stringValue()),
                    evidence.required("amountMinor").longValue(), evidence.required("clearingReference").stringValue());
            var businessDate = LocalDate.parse(evidence.required("settlementBusinessDate").stringValue());
            mq.send(REPLY, priorSettlement, businessDate);
            mq.send(REPLY, priorSettlement, businessDate);

            UUID unknown = UUID.randomUUID();
            var uncorrelated = new ClearingRecord(ClearingRecord.Kind.SETTLED, unknown, 123, "C00000000001");
            mq.send(REPLY, uncorrelated, businessDate);

            var malformed = context.createBytesMessage();
            malformed.setJMSCorrelationID(unknown.toString());
            malformed.writeBytes(new byte[]{0x01, 0x02});
            context.createProducer().setDeliveryMode(DeliveryMode.PERSISTENT)
                    .send(context.createQueue(REQUEST), malformed);
            context.commit();

            Instant deadline = Instant.now().plus(Duration.ofSeconds(45));
            int after = before;
            while (Instant.now().isBefore(deadline) && (after = depth(browser)) < before + 3) {
                Thread.sleep(500);
            }
            // The one logical rejection is also unknown to payments. Its presence in backout
            // proves the queued instruction ran, and prevents consumers from silently discarding it.
            int rejections = 0;
            var messages = browser.getEnumeration();
            while (messages.hasMoreElements()) {
                var message = (jakarta.jms.Message) messages.nextElement();
                if (id.toString().equals(message.getJMSCorrelationID())) rejections++;
            }
            assertTrue(after >= before + 3, "malformed and unknown records did not reach backout");
            assertEquals(1, rejections, "duplicate instructions generated more than one clearing decision");
        }
    }

    private static JMSContext raw(String address, String password) throws Exception {
        return new ActiveMQConnectionFactory(address).createContext("app", password, JMSContext.SESSION_TRANSACTED);
    }

    private static int depth(jakarta.jms.QueueBrowser browser) throws Exception {
        int count = 0;
        var messages = browser.getEnumeration();
        while (messages.hasMoreElements()) {
            messages.nextElement();
            count++;
        }
        return count;
    }
}
