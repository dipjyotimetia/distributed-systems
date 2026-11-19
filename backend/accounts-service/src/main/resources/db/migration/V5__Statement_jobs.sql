CREATE TABLE accounts_service.statement_jobs (
    id uuid PRIMARY KEY,
    account_id bigint NOT NULL,
    customer_id bigint NOT NULL,
    idempotency_key text NOT NULL,
    status text NOT NULL DEFAULT 'QUEUED' CHECK (status IN ('QUEUED', 'READY', 'FAILED', 'EXPIRED')),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    expires_at timestamptz,
    document bytea,
    UNIQUE (account_id, customer_id, idempotency_key),
    CHECK ((status = 'READY') = (document IS NOT NULL))
);

CREATE INDEX statement_jobs_by_account
    ON accounts_service.statement_jobs (account_id, customer_id, created_at DESC, id DESC);

CREATE TABLE accounts_service.statement_job_outbox (
    job_id uuid PRIMARY KEY REFERENCES accounts_service.statement_jobs (id),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
