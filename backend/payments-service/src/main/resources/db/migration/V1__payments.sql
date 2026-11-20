CREATE TABLE payments.payment (
    id uuid PRIMARY KEY,
    customer_id bigint NOT NULL CHECK (customer_id > 0),
    idempotency_key varchar(128) NOT NULL,
    account_id bigint NOT NULL CHECK (account_id > 0),
    destination varchar(12) NOT NULL CHECK (destination ~ '^[A-Z0-9]{12}$'),
    amount_minor bigint NOT NULL CHECK (amount_minor BETWEEN 1 AND 999999999999),
    status text NOT NULL CHECK (status IN ('PENDING','SUBMITTED','ACCEPTED','SETTLED','REJECTED','REVIEW_REQUIRED')),
    clearing_reference varchar(12),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (customer_id, idempotency_key)
);

CREATE TABLE payments.outbox (
    id uuid PRIMARY KEY,
    payment_id uuid NOT NULL REFERENCES payments.payment(id),
    destination text NOT NULL CHECK (destination IN ('WORKFLOW','MQ','KAFKA')),
    body bytea NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    published_at timestamptz
);
CREATE INDEX payment_outbox_pending ON payments.outbox (destination, created_at, id) WHERE published_at IS NULL;

CREATE TABLE payments.transition (
    id uuid PRIMARY KEY,
    payment_id uuid NOT NULL REFERENCES payments.payment(id),
    status text NOT NULL,
    reason text NOT NULL,
    occurred_at timestamptz NOT NULL DEFAULT now()
);
