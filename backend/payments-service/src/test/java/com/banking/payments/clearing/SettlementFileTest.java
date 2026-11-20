package com.banking.payments.clearing;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SettlementFileTest {
    private static final String RECORD = "01S597a4c24-019f-4ed6-884f-bf7cf8486f65AUD000000000025REF000000001";
    private static final String FILE = "01H20240229000007AUD\n" + RECORD + "\n" + RECORD + "\n"
            + "01T000002000000000000000050\n";

    @Test
    void preservesDuplicateEvidenceForReconciliation() {
        SettlementFile decoded = SettlementFile.decode(bytes(FILE));
        assertEquals(LocalDate.of(2024, 2, 29), decoded.businessDate());
        assertEquals(7, decoded.sequence());
        assertEquals(50, decoded.totalMinor());
        assertEquals(2, decoded.records().size());
        assertEquals(decoded.records().get(0), decoded.records().get(1));
        assertEquals(FILE, new String(decoded.encode(), StandardCharsets.US_ASCII));
    }

    @Test
    void handlesEmptyBusinessDay() {
        var file = new SettlementFile(LocalDate.of(2026, 11, 20), 1, List.of());
        assertEquals(file, SettlementFile.decode(file.encode()));
    }

    @Test
    void rejectsInvalidEvidence() {
        assertInvalid(FILE.replace("20240229", "20250229"));
        assertInvalid(FILE.replace("000007AUD", "000000AUD"));
        assertInvalid(FILE.replace("01T000002", "01T000001"));
        assertInvalid(FILE.replace("000000000000000050", "000000000000000051"));
        assertInvalid(FILE.replace("01S", "01A"));
        assertInvalid(FILE.replace("\n", "\r\n"));
        assertInvalid(FILE.substring(0, FILE.length() - 1));
        assertThrows(IllegalArgumentException.class, () -> new SettlementFile(LocalDate.of(2026, 11, 20), 1,
                List.of(new ClearingRecord(ClearingRecord.Kind.ACCEPTED, UUID.randomUUID(), 25, "REF000000001"))));
    }

    private static void assertInvalid(String file) {
        assertThrows(IllegalArgumentException.class, () -> SettlementFile.decode(bytes(file)));
    }

    private static byte[] bytes(String file) {
        return file.getBytes(StandardCharsets.US_ASCII);
    }
}
