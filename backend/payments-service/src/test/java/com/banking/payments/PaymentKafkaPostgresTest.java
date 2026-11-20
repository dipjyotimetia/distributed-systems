package com.banking.payments;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariables;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariables({
        @EnabledIfEnvironmentVariable(named = "PAYMENTS_TEST_KAFKA_BROKERS", matches = ".+"),
        @EnabledIfEnvironmentVariable(named = "PAYMENTS_KAFKA_TEST_DB_URL", matches = ".+")
})
class PaymentKafkaPostgresTest {
    @Test
    void acknowledgedKafkaEventIsRetriedWithItsOriginalIdentityAfterDatabaseRollback() throws Exception {
        String brokers = System.getenv("PAYMENTS_TEST_KAFKA_BROKERS");
        String topic = "payment-ack-test-" + UUID.randomUUID();
        try (var admin = Admin.create(Map.of("bootstrap.servers", brokers));
             var context = new SpringApplicationBuilder(PaymentsApplication.class)
                     .properties("JWT_SECRET=synthetic-payments-kafka-test-123456789", "server.port=0")
                     .run("--spring.datasource.url=" + System.getenv("PAYMENTS_KAFKA_TEST_DB_URL"),
                             "--spring.flyway.create-schemas=true")) {
            admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1))).all().get(15, TimeUnit.SECONDS);
            try {
                var db = context.getBean(JdbcTemplate.class);
                var tx = context.getBean(TransactionTemplate.class);
                assertEquals(0, db.queryForObject("""
                        SELECT count(*) FROM payments.outbox WHERE destination = 'KAFKA' AND published_at IS NULL
                        """, Integer.class), "use an isolated test database with no pending Kafka outbox");
                var payment = context.getBean(PaymentStore.class).initiate(440, UUID.randomUUID().toString(),
                        new PaymentStore.Instruction(10, "DEST00000001", 25));
                db.update("UPDATE payments.payment SET trace_parent = ? WHERE id = ?",
                        "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01", payment.id());
                var publisher = new PaymentEventPublisher(db, context.getBean(PaymentTracing.class), brokers, topic);
                try {
                    assertThrows(AcknowledgementRollback.class, () -> tx.executeWithoutResult(status -> {
                        publisher.publish();
                        throw new AcknowledgementRollback();
                    }));
                    assertEquals(1, pending(db, payment.id()));
                    tx.executeWithoutResult(status -> publisher.publish());
                    assertEquals(0, pending(db, payment.id()));
                } finally {
                    publisher.close();
                }

                var records = consume(brokers, topic);
                assertEquals(2, records.size());
                assertEquals(payment.id().toString(), records.getFirst().key());
                assertEquals(records.getFirst().key(), records.getLast().key());
                assertArrayEquals(records.getFirst().value(), records.getLast().value());
                var eventId = db.queryForObject("""
                        SELECT id FROM payments.outbox WHERE payment_id = ? AND destination = 'KAFKA'
                        """, UUID.class, payment.id());
                assertTrue(new String(records.getFirst().value(), StandardCharsets.UTF_8).contains(eventId.toString()));
                for (var record : records) {
                    var trace = record.headers().lastHeader("traceparent");
                    assertNotNull(trace);
                    assertTrue(new String(trace.value(), StandardCharsets.US_ASCII)
                            .startsWith("00-4bf92f3577b34da6a3ce929d0e0e4736-"));
                }
            } finally {
                admin.deleteTopics(List.of(topic)).all().get(15, TimeUnit.SECONDS);
            }
        }
    }

    private static int pending(JdbcTemplate db, UUID id) {
        return db.queryForObject("""
                SELECT count(*) FROM payments.outbox WHERE payment_id = ? AND destination = 'KAFKA'
                    AND published_at IS NULL
                """, Integer.class, id);
    }

    private static List<ConsumerRecord<String, byte[]>> consume(String brokers, String topic) {
        try (var consumer = new KafkaConsumer<>(Map.of("bootstrap.servers", brokers),
                new StringDeserializer(), new ByteArrayDeserializer())) {
            var partition = new TopicPartition(topic, 0);
            consumer.assign(List.of(partition));
            consumer.seekToBeginning(List.of(partition));
            var records = new ArrayList<ConsumerRecord<String, byte[]>>();
            Instant deadline = Instant.now().plusSeconds(10);
            while (records.size() < 2 && Instant.now().isBefore(deadline)) {
                consumer.poll(Duration.ofMillis(500)).forEach(records::add);
            }
            return records;
        }
    }

    private static final class AcknowledgementRollback extends RuntimeException {}
}
