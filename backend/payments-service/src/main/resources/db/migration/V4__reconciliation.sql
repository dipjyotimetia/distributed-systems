CREATE TABLE payments.reconciliation_run (
    business_date date PRIMARY KEY,
    file_sequence integer UNIQUE NOT NULL,
    sha256 char(64) NOT NULL,
    evidence bytea NOT NULL,
    completed_at timestamptz NOT NULL DEFAULT clock_timestamp()
);

CREATE TABLE payments.reconciliation_result (
    business_date date NOT NULL REFERENCES payments.reconciliation_run(business_date),
    record_number integer NOT NULL,
    payment_id uuid NOT NULL,
    outcome text NOT NULL CHECK (outcome IN ('MATCHED','DUPLICATE','MISSING_PAYMENT','MISSING_RECORD','AMOUNT_MISMATCH','REFERENCE_MISMATCH','UNEXPECTED_STATE')),
    expected_amount_minor bigint,
    observed_amount_minor bigint,
    observed_reference varchar(12),
    PRIMARY KEY (business_date, record_number)
);
CREATE INDEX reconciliation_operator_queue ON payments.reconciliation_result (business_date DESC, record_number)
    WHERE outcome <> 'MATCHED';
