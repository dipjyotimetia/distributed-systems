ALTER TABLE payments.payment
    ADD COLUMN screened_at timestamptz,
    ADD COLUMN reserved_at timestamptz;

CREATE UNIQUE INDEX payment_one_instruction ON payments.outbox(payment_id) WHERE destination = 'MQ';
