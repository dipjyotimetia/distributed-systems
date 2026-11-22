package com.banking.payments;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class SchemaRegistryAvroEncoderTest {

    @Test
    void jsonModeLeavesBodyUnchanged() throws Exception {
        SchemaRegistryAvroEncoder encoder = new SchemaRegistryAvroEncoder("", "json", "payment-events.v1");
        byte[] body = "{\"version\":1}".getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(body, encoder.encode(body));
    }

    @Test
    void confluentWireUsesMagicByte() {
        byte[] wire = SchemaRegistryAvroEncoder.confluentWire(3, new byte[]{9});
        assertEquals(0, wire[0]);
        assertEquals(6, wire.length);
        assertEquals(9, wire[5]);
    }
}
