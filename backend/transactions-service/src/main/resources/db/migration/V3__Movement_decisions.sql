CREATE TABLE transactions_service.movement_decisions (
    operation_id UUID PRIMARY KEY,
    user_id BIGINT NOT NULL,
    from_account_id BIGINT,
    to_account_id BIGINT,
    amount NUMERIC(19,2) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    state VARCHAR(16) NOT NULL CHECK (state IN ('STARTED', 'COMMITTED', 'ABORTED')),
    ledger_id BIGINT REFERENCES transactions_service.transactions (id),
    finalized BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE INDEX movement_decisions_recovery ON transactions_service.movement_decisions (user_id, finalized, created_at);
