CREATE TABLE clearing.instruction (
    payment_id uuid PRIMARY KEY,
    amount_minor bigint NOT NULL CHECK (amount_minor BETWEEN 1 AND 999999999999),
    destination varchar(12) NOT NULL,
    reference varchar(12) UNIQUE NOT NULL,
    received_at timestamptz NOT NULL DEFAULT now()
);
CREATE SEQUENCE clearing.reference_seq MAXVALUE 99999999999;

CREATE TABLE clearing.reply (
    payment_id uuid NOT NULL REFERENCES clearing.instruction(payment_id),
    kind text NOT NULL CHECK (kind IN ('ACCEPTED','SETTLED','REJECTED')),
    due_at timestamptz NOT NULL,
    published_at timestamptz,
    PRIMARY KEY (payment_id, kind)
);
CREATE INDEX clearing_reply_due ON clearing.reply (due_at) WHERE published_at IS NULL;
