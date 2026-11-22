package com.banking.transactionsservice.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KafkaSaslConfigTest {

    @Test
    void plainJaasEscapesQuotesAndBackslashes() {
        String jaas = KafkaSaslConfig.plainJaas("bank\"ing", "sec\\ret");
        assertTrue(jaas.startsWith("org.apache.kafka.common.security.plain.PlainLoginModule required"));
        assertEquals(
                "org.apache.kafka.common.security.plain.PlainLoginModule required username=\"bank\\\"ing\" password=\"sec\\\\ret\";",
                jaas);
    }
}
