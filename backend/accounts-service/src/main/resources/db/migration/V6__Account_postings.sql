CREATE TABLE accounts_service.account_postings (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id BIGINT NOT NULL REFERENCES accounts_service.accounts(id),
    operation_id UUID NOT NULL,
    currency VARCHAR(3) NOT NULL,
    amount NUMERIC(19,2) NOT NULL CHECK (amount <> 0),
    balance_after NUMERIC(19,2) NOT NULL,
    source VARCHAR(24) NOT NULL,
    user_id BIGINT NOT NULL,
    posted_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (account_id, operation_id)
);

CREATE INDEX account_postings_period ON accounts_service.account_postings(account_id, posted_at, id);

ALTER TABLE accounts_service.movement_reservations ADD COLUMN requested_currency VARCHAR(3);
ALTER TABLE accounts_service.movement_reservations ADD COLUMN requested_scope VARCHAR(8);
ALTER TABLE accounts_service.movement_reservations ADD COLUMN reserved_at TIMESTAMPTZ DEFAULT clock_timestamp();
CREATE INDEX movement_reservations_active_debits ON accounts_service.movement_reservations(reserved_at)
    WHERE state = 'RESERVED' AND from_account_id IS NOT NULL;

CREATE TABLE accounts_service.posting_coverage (
    singleton BOOLEAN PRIMARY KEY CHECK (singleton),
    starts_at TIMESTAMPTZ NOT NULL
);
INSERT INTO accounts_service.posting_coverage VALUES (TRUE, clock_timestamp());

ALTER TABLE accounts_service.statement_jobs ADD COLUMN period_start TIMESTAMPTZ;
ALTER TABLE accounts_service.statement_jobs ADD COLUMN period_end TIMESTAMPTZ;
ALTER TABLE accounts_service.statement_jobs ADD CONSTRAINT statement_job_valid_period
    CHECK ((period_start IS NULL AND period_end IS NULL)
        OR (period_start IS NOT NULL AND period_end IS NOT NULL AND period_start < period_end));
