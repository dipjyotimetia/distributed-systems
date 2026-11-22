package com.banking.transactionsservice.event;

import com.banking.events.TransactionEvent;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.specific.SpecificDatumWriter;
import org.apache.avro.util.ClassSecurityValidator;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class SchemaRegistryAvroEncoderTest {

    @Test
    void trustsGeneratedEventClassesForAvroSerialize() throws Exception {
        SchemaRegistryAvroEncoder.trustGeneratedEventClasses();
        assertDoesNotThrow(() -> ClassSecurityValidator.validate(TransactionEvent.class));

        TransactionEvent event = TransactionEvent.newBuilder()
                .setTransactionId(1L)
                .setAccountId(2L)
                .setUserId(3L)
                .setAmount(1.25)
                .setTransactionType("DEPOSIT")
                .setStatus("COMPLETED")
                .setTimestamp("2026-11-22T00:00:00Z")
                .build();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        var encoder = EncoderFactory.get().binaryEncoder(out, null);
        new SpecificDatumWriter<TransactionEvent>(event.getSchema()).write(event, encoder);
        encoder.flush();
        assertTrue(out.size() > 0);
    }

    @Test
    void jsonModePassesThroughUtf8Bytes() throws Exception {
        SchemaRegistryAvroEncoder encoder = new SchemaRegistryAvroEncoder("", "json");
        byte[] value = encoder.encode("transaction-events", "{\"transaction_id\":1}");
        assertEquals("{\"transaction_id\":1}", new String(value, StandardCharsets.UTF_8));
        assertFalse(encoder.enabled());
    }

    @Test
    void confluentWirePrefixesMagicAndSchemaId() {
        byte[] payload = SchemaRegistryAvroEncoder.confluentWire(99, new byte[]{1, 2, 3});
        assertEquals(0, payload[0]);
        assertEquals(99, ByteBuffer.wrap(payload, 1, 4).getInt());
        assertArrayEquals(new byte[]{1, 2, 3}, Arrays.copyOfRange(payload, 5, payload.length));
    }

    @Test
    void movementTopicPassesThroughWhenRegistryMissing() throws Exception {
        SchemaRegistryAvroEncoder encoder = new SchemaRegistryAvroEncoder("", "avro");
        String json = "{\"schemaVersion\":1,\"eventId\":\"e1\",\"movementId\":\"m1\",\"type\":\"DEPOSIT\","
                + "\"money\":{\"currency\":\"AUD\",\"amount\":\"1.00\"},\"status\":\"PENDING\","
                + "\"occurredAt\":\"2026-11-21T01:00:01Z\"}";
        byte[] value = encoder.encode("movement-lifecycle-v1", json);
        assertEquals(json, new String(value, StandardCharsets.UTF_8));
        assertFalse(encoder.enabled());
    }

    @Test
    void avroIsEnabledByDefaultWhenRegistryConfigured() {
        assertTrue(new SchemaRegistryAvroEncoder("http://banking-kafka:8081", "avro").enabled());
    }
}
