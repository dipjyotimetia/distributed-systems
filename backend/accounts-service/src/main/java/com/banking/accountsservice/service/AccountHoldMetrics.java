package com.banking.accountsservice.service;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
class AccountHoldMetrics {
    AccountHoldMetrics(JdbcTemplate jdbc, MeterRegistry registry) {
        Gauge.builder("accounts.holds.oldest.seconds", jdbc, database -> database.queryForObject("""
                SELECT COALESCE(EXTRACT(EPOCH FROM (clock_timestamp() - MIN(reserved_at))), 0)
                FROM accounts_service.movement_reservations
                WHERE state = 'RESERVED' AND from_account_id IS NOT NULL
                """, Double.class)).register(registry);
    }
}
