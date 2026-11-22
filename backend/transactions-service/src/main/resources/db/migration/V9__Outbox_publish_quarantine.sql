ALTER TABLE transactions_service.event_outbox
    ADD COLUMN IF NOT EXISTS publish_attempts INT NOT NULL DEFAULT 0;
ALTER TABLE transactions_service.event_outbox
    ADD COLUMN IF NOT EXISTS last_error VARCHAR(1000);
ALTER TABLE transactions_service.event_outbox
    ADD COLUMN IF NOT EXISTS failed_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS event_outbox_unpublished_ready
    ON transactions_service.event_outbox (id)
    WHERE published_at IS NULL AND failed_at IS NULL;
