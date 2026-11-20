package com.banking.payments.settlement;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.*;

@Component
public record BusinessDay(ZoneId zone, LocalTime cutoff) {
    @org.springframework.beans.factory.annotation.Autowired
    public BusinessDay(@Value("${SETTLEMENT_ZONE:Australia/Sydney}") String zone,
                       @Value("${SETTLEMENT_CUTOFF:17:00}") String cutoff) {
        this(ZoneId.of(zone), LocalTime.parse(cutoff));
    }

    public LocalDate latestClosed(Instant instant) {
        var local = instant.atZone(zone);
        return instant.isBefore(end(local.toLocalDate())) ? local.toLocalDate().minusDays(1) : local.toLocalDate();
    }

    public Instant end(LocalDate date) {
        return date.atTime(cutoff).atZone(zone).toInstant();
    }
}
