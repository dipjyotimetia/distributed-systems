package com.banking.payments.clearing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "PAYMENTS_TEST_ARTEMIS_ADDRESS", matches = ".+")
class ArtemisTransportTest {
    @Test
    void roundTripsSettlementAndQuarantinesOnlyAfterThreeDeliveries() throws Exception {
        var transport = MqClearingTransport.connect(System.getenv("PAYMENTS_TEST_ARTEMIS_ADDRESS"), "app",
                System.getenv("BANKING_MQ_APP_PASSWORD"));
        var record = new ClearingRecord(ClearingRecord.Kind.SETTLED, UUID.randomUUID(), 2500, "C00000000001");
        var date = LocalDate.of(2026, 11, 20);
        String reply = "DEV.QUEUE.PAYMENTS.REPLY";
        String backout = "DEV.QUEUE.PAYMENTS.BACKOUT";

        transport.send(reply, record, date);
        assertTrue(transport.receive(reply, backout, 2000, (actual, metadata) -> {
            assertEquals(record, actual);
            assertEquals(date, metadata.businessDate());
        }));

        transport.send(reply, record, date);
        for (int attempt = 0; attempt < 3; attempt++) {
            assertThrows(MqClearingTransport.InvalidRecord.class,
                    () -> transport.receive(reply, backout, 2000, (actual, metadata) -> {
                        throw new MqClearingTransport.InvalidRecord("Synthetic invalid evidence");
                    }));
        }
        assertFalse(transport.receive(reply, backout, 100, (actual, metadata) -> fail("Message must leave reply queue")));
        assertTrue(transport.receive(backout, reply, 2000, (actual, metadata) -> {
            assertEquals(record, actual);
            assertEquals(date, metadata.businessDate());
        }));
    }
}
