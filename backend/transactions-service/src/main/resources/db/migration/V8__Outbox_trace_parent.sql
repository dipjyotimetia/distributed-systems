ALTER TABLE transactions_service.event_outbox
    ADD COLUMN IF NOT EXISTS trace_parent VARCHAR(128);
