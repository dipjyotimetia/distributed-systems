package com.banking.transactionsservice.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FraudServiceClientTest {

    @Test
    void failOpenApprovesWhenFraudIsDown() {
        var response = new FraudServiceClient(null, true, "token").unavailable("1", "2", new IllegalStateException("down"));
        assertTrue(response.getApproved());
        assertTrue(response.getReason().contains("fail-open"));
    }

    @Test
    void failClosedRejectsWhenFraudIsDown() {
        var response = new FraudServiceClient(null, false, "token").unavailable("1", "2", new IllegalStateException("down"));
        assertFalse(response.getApproved());
        assertTrue(response.getReason().contains("fail-closed"));
    }
}
