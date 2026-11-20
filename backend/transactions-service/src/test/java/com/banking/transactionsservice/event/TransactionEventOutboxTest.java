package com.banking.transactionsservice.event;

import com.banking.transactionsservice.entity.Transaction;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TransactionEventOutboxTest {

    @Test
    void payloadKeepsTheNotificationContract() {
        Transaction transaction = new Transaction(7L, null, 4L, 12.5,
                Transaction.TransactionType.DEPOSIT, "demo");
        transaction.setId(3L);
        transaction.setStatus(Transaction.TransactionStatus.COMPLETED);
        transaction.setProcessedAt(LocalDateTime.parse("2026-11-20T09:30:00"));

        String payload = TransactionEventOutbox.payload(transaction);

        assertTrue(payload.contains("\"transaction_id\":3"));
        assertTrue(payload.contains("\"account_id\":4"));
        assertTrue(payload.contains("\"user_id\":7"));
        assertTrue(payload.contains("\"transaction_type\":\"DEPOSIT\""));
        assertTrue(payload.contains("\"status\":\"COMPLETED\""));
        assertTrue(payload.contains("\"timestamp\":\"2026-11-20T09:30Z\""));
    }
}
