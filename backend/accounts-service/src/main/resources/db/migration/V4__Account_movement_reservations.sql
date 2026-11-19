CREATE TABLE accounts_service.movement_reservations (
    operation_id UUID PRIMARY KEY,
    user_id BIGINT NOT NULL,
    from_account_id BIGINT,
    to_account_id BIGINT,
    amount NUMERIC(19,2) NOT NULL CHECK (amount > 0),
    original_from NUMERIC(19,2),
    original_to NUMERIC(19,2),
    state VARCHAR(16) NOT NULL CHECK (state IN ('RESERVED', 'COMMITTED', 'COMPENSATED'))
);

CREATE INDEX movement_reservations_from ON accounts_service.movement_reservations (from_account_id, state);
CREATE INDEX movement_reservations_to ON accounts_service.movement_reservations (to_account_id, state);
