CREATE TABLE transactions_service.customer_movements (
    id UUID PRIMARY KEY,
    user_id BIGINT NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    type VARCHAR(16) NOT NULL CHECK (type IN ('DEPOSIT', 'WITHDRAWAL', 'TRANSFER')),
    from_account_id BIGINT,
    to_account_id BIGINT,
    currency VARCHAR(3) NOT NULL,
    amount NUMERIC(19,2) NOT NULL CHECK (amount > 0),
    description VARCHAR(255),
    stage VARCHAR(24) NOT NULL DEFAULT 'SCREENING'
        CHECK (stage IN ('SCREENING', 'RESERVE', 'POST', 'POSTED', 'REJECTED')),
    screening_decision VARCHAR(48),
    reason_code VARCHAR(48),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    next_attempt TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (user_id, idempotency_key)
);
CREATE INDEX customer_movements_work ON transactions_service.customer_movements(next_attempt, id)
    WHERE stage IN ('SCREENING', 'RESERVE', 'POST');

CREATE TABLE transactions_service.movement_screening_audit (
    movement_id UUID NOT NULL REFERENCES transactions_service.customer_movements(id),
    decision VARCHAR(48) NOT NULL,
    policy VARCHAR(32) NOT NULL,
    attempts BIGINT NOT NULL DEFAULT 1,
    first_seen TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    last_seen TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (movement_id, decision)
);

ALTER TABLE transactions_service.event_outbox ALTER COLUMN transaction_id DROP NOT NULL;
ALTER TABLE transactions_service.event_outbox ADD COLUMN topic VARCHAR(128);
ALTER TABLE transactions_service.event_outbox ADD COLUMN movement_id UUID
    REFERENCES transactions_service.customer_movements(id);
ALTER TABLE transactions_service.event_outbox ADD CONSTRAINT event_outbox_owner
    CHECK ((transaction_id IS NOT NULL AND topic IS NULL AND movement_id IS NULL)
        OR (transaction_id IS NULL AND topic IS NOT NULL AND movement_id IS NOT NULL));
