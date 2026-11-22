package com.banking.transactionsservice.event;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class SchemaRegistryAvroEncoderTest {

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
