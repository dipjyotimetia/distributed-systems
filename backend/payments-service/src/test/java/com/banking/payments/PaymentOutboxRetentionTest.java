package com.banking.payments;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentOutboxRetentionTest {

    @Test
    void cleanupPublishedDeletesOldRows() {
        JdbcTemplate db = mock(JdbcTemplate.class);
        when(db.update(contains("DELETE FROM payments.outbox"), eq(14))).thenReturn(2);
        new PaymentOutboxRetention(db, 14).cleanupPublished();
        verify(db).update(contains("DELETE FROM payments.outbox"), eq(14));
    }
}
