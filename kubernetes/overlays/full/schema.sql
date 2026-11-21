CREATE TABLE IF NOT EXISTS disputes.cases (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    transfer_id bigint NOT NULL UNIQUE,
    customer_id bigint NOT NULL,
    reason text NOT NULL CHECK (char_length(reason) BETWEEN 1 AND 500),
    status text NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'ESCALATED', 'RESOLVED')),
    created_at timestamptz NOT NULL DEFAULT now(),
    escalated_at timestamptz,
    resolved_at timestamptz,
    resolved_by bigint,
    resolution text
);

CREATE INDEX IF NOT EXISTS disputes_customer_idx ON disputes.cases (customer_id, id DESC);
CREATE INDEX IF NOT EXISTS disputes_queue_idx ON disputes.cases (status, id)
    WHERE status IN ('OPEN', 'ESCALATED');

CREATE TABLE IF NOT EXISTS disputes.workflow_outbox (
    case_id bigint PRIMARY KEY REFERENCES disputes.cases (id),
    deadline timestamptz NOT NULL
);

-- A null escalation deadline identifies cases started by the legacy workflow.
ALTER TABLE disputes.cases
    ADD COLUMN IF NOT EXISTS escalation_deadline timestamptz,
    ADD COLUMN IF NOT EXISTS revision bigint NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS evidence jsonb,
    ADD COLUMN IF NOT EXISTS evidence_deadline timestamptz,
    ADD COLUMN IF NOT EXISTS evidence_closed_at timestamptz,
    ADD COLUMN IF NOT EXISTS ai_draft jsonb;

CREATE TABLE IF NOT EXISTS disputes.signal_outbox (
    case_id bigint PRIMARY KEY REFERENCES disputes.cases (id) ON DELETE CASCADE,
    revision bigint NOT NULL
);
