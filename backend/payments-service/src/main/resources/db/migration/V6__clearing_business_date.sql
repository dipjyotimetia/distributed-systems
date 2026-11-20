ALTER TABLE payments.payment ADD COLUMN settlement_business_date date;
ALTER TABLE payments.clearing_inbox ADD COLUMN business_date date;
CREATE INDEX payment_settlement_business_date ON payments.payment (settlement_business_date, id)
    WHERE status = 'SETTLED';

ALTER TABLE payments.reconciliation_result DROP CONSTRAINT reconciliation_result_outcome_check;
ALTER TABLE payments.reconciliation_result ADD CONSTRAINT reconciliation_result_outcome_check
    CHECK (outcome IN ('MATCHED','DUPLICATE','MISSING_PAYMENT','MISSING_RECORD','AMOUNT_MISMATCH',
                      'REFERENCE_MISMATCH','BUSINESS_DATE_MISMATCH','UNEXPECTED_STATE'));
