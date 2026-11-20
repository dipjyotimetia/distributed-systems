package com.banking.payments.settlement;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;

@Component
@Profile("reconciliation")
public class ReconciliationJob {
    private final JdbcTemplate db;
    private final BusinessDay calendar;
    private final SettlementObjects objects;
    private final Reconciliation reconciliation;
    private final String explicitDate;
    private final String firstDate;

    public ReconciliationJob(JdbcTemplate db, BusinessDay calendar, SettlementObjects objects,
                             Reconciliation reconciliation, @Value("${SETTLEMENT_BUSINESS_DATE:}") String explicitDate,
                             @Value("${SETTLEMENT_FIRST_DATE:}") String firstDate) {
        this.db = db;
        this.calendar = calendar;
        this.objects = objects;
        this.reconciliation = reconciliation;
        this.explicitDate = explicitDate;
        this.firstDate = firstDate;
    }

    public void run() {
        LocalDate latest = calendar.latestClosed(Instant.now());
        if (!explicitDate.isBlank()) {
            LocalDate date = LocalDate.parse(explicitDate);
            if (date.isAfter(latest)) throw new IllegalArgumentException("Business date has not closed");
            reconcile(date);
            return;
        }
        reconciliation.recordLateDiscrepancies();
        LocalDate last = db.queryForObject("SELECT max(business_date) FROM payments.reconciliation_run", LocalDate.class);
        LocalDate start = last == null ? (firstDate.isBlank() ? latest : LocalDate.parse(firstDate)) : last.plusDays(1);
        for (LocalDate date = start; !date.isAfter(latest); date = date.plusDays(1)) {
            reconcile(date);
        }
    }

    private void reconcile(LocalDate date) {
        byte[] bytes = objects.get(date).orElseThrow(() -> new IllegalStateException("Settlement file unavailable for " + date));
        reconciliation.run(date, bytes);
    }
}
