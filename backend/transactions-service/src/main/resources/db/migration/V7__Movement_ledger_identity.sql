ALTER TABLE transactions_service.transactions
    ALTER COLUMN amount TYPE NUMERIC(19,2) USING round(amount::numeric, 2);

ALTER TABLE transactions_service.transactions
    ADD COLUMN movement_id UUID UNIQUE REFERENCES transactions_service.customer_movements(id);

ALTER TABLE transactions_service.event_outbox ADD COLUMN event_key VARCHAR(80);
CREATE UNIQUE INDEX event_outbox_event_key ON transactions_service.event_outbox (event_key);
