CREATE TABLE accounts_service.account_postings (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id BIGINT NOT NULL REFERENCES accounts_service.accounts(id),
    operation_id UUID NOT NULL,
    currency VARCHAR(3) NOT NULL,
    amount NUMERIC(19,2) NOT NULL,
    balance_after NUMERIC(19,2) NOT NULL,
    source VARCHAR(24) NOT NULL,
    user_id BIGINT NOT NULL,
    UNIQUE (account_id, operation_id)
);

ALTER TABLE accounts_service.movement_reservations ADD COLUMN requested_currency VARCHAR(3);
ALTER TABLE accounts_service.movement_reservations ADD COLUMN requested_scope VARCHAR(8);
