package com.banking.payments;

import com.banking.events.PaymentEvent;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.specific.SpecificDatumWriter;
import org.apache.avro.util.ClassSecurityValidator;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class SchemaRegistryAvroEncoderTest {

    @Test
    void trustsGeneratedEventClassesForAvroSerialize() throws Exception {
        SchemaRegistryAvroEncoder.trustGeneratedEventClasses();
        assertDoesNotThrow(() -> ClassSecurityValidator.validate(PaymentEvent.class));

        PaymentEvent event = PaymentEvent.newBuilder()
                .setVersion(1)
                .setEventId("e1")
                .setPaymentId("p1")
                .setCustomerId(9L)
                .setAmountMinor(100L)
                .setCurrency("AUD")
                .setStatus("CLEARED")
                .setClearingReference(null)
                .setSettlementBusinessDate(null)
                .setOccurredAt("2026-11-22T00:00:00Z")
                .build();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        var encoder = EncoderFactory.get().binaryEncoder(out, null);
        new SpecificDatumWriter<>(PaymentEvent.getClassSchema()).write(event, encoder);
        encoder.flush();
        assertTrue(out.size() > 0);
    }

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
