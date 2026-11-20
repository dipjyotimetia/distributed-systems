CREATE UNIQUE INDEX reconciliation_one_missing_payment_per_day
    ON payments.reconciliation_result (business_date, payment_id)
    WHERE outcome = 'MISSING_RECORD';

CREATE INDEX reconciliation_payment_evidence
    ON payments.reconciliation_result (business_date, payment_id);
