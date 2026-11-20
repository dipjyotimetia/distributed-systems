CREATE TABLE payments.clearing_inbox (
    payment_id uuid NOT NULL REFERENCES payments.payment(id),
    kind text NOT NULL CHECK (kind IN ('ACCEPTED','SETTLED','REJECTED')),
    amount_minor bigint NOT NULL CHECK (amount_minor BETWEEN 1 AND 999999999999),
    reference varchar(12) NOT NULL,
    received_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (payment_id, kind, amount_minor, reference)
);
