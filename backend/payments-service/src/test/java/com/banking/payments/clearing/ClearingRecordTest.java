package com.banking.payments.clearing;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ClearingRecordTest {
    private static final UUID PAYMENT_ID = UUID.fromString("597a4c24-019f-4ed6-884f-bf7cf8486f65");
    private static final String INSTRUCTION = "01I597a4c24-019f-4ed6-884f-bf7cf8486f65AUD000000000025DEST00000001";

    @Test
    void encodesCanonicalAudInstruction() {
        ClearingRecord instruction = new ClearingRecord(ClearingRecord.Kind.INSTRUCTION, PAYMENT_ID, 25,
                "DEST00000001");
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-EG"));
            assertArrayEquals(ascii(INSTRUCTION), instruction.encode());
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void roundTripsAllKindsAndBoundaryAmounts() {
        for (ClearingRecord.Kind kind : ClearingRecord.Kind.values()) {
            for (long amount : new long[]{1, 25, ClearingRecord.MAX_AMOUNT_MINOR}) {
                ClearingRecord record = new ClearingRecord(kind, PAYMENT_ID, amount, "REF000000001");
                assertEquals(record, ClearingRecord.decode(record.encode()));
            }
        }
    }

    @Test
    void rejectsMalformedRecords() {
        rejects(ascii(INSTRUCTION.substring(1)));
        rejects(ascii(INSTRUCTION + "\n"));
        rejects(ascii("02" + INSTRUCTION.substring(2)));
        rejects(ascii("01X" + INSTRUCTION.substring(3)));
        rejects(ascii(INSTRUCTION.replace("AUD", "USD")));
        rejects(ascii(INSTRUCTION.replace("597a", "597A")));
        rejects(ascii(INSTRUCTION.replace("000000000025", "000000000000")));
        rejects(ascii(INSTRUCTION.replace("000000000025", "0000000000-1")));
        byte[] nonAscii = ascii(INSTRUCTION);
        nonAscii[nonAscii.length - 1] = (byte) 0xFF;
        rejects(nonAscii);
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    private static void rejects(byte[] bytes) {
        assertThrows(IllegalArgumentException.class, () -> ClearingRecord.decode(bytes));
    }
}
