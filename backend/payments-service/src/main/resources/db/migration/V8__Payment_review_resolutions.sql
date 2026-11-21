ALTER TABLE payments.payment ADD COLUMN review_revision BIGINT NOT NULL DEFAULT 0;
ALTER TABLE payments.payment ADD COLUMN late_exception_at TIMESTAMPTZ;
ALTER TABLE payments.clearing_inbox ADD COLUMN evidence_id UUID NOT NULL DEFAULT gen_random_uuid() UNIQUE;

CREATE TABLE payments.review_resolution (
    id UUID PRIMARY KEY,
    payment_id UUID NOT NULL UNIQUE REFERENCES payments.payment(id),
    operator_id BIGINT NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    expected_revision BIGINT NOT NULL,
    evidence_id UUID NOT NULL REFERENCES payments.clearing_inbox(evidence_id),
    action VARCHAR(24) NOT NULL CHECK (action IN ('CONFIRM_SETTLEMENT','CONFIRM_REJECTION')),
    reason VARCHAR(1000) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    executed_at TIMESTAMPTZ,
    UNIQUE (operator_id, idempotency_key)
);

CREATE FUNCTION payments.revise_for_evidence() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    UPDATE payments.payment SET review_revision = review_revision + 1,
        late_exception_at = CASE
            WHEN (status = 'SETTLED' AND NEW.kind = 'REJECTED')
              OR (status = 'REJECTED' AND NEW.kind = 'SETTLED')
              OR (status IN ('SETTLED', 'REJECTED') AND
                  (amount_minor <> NEW.amount_minor OR
                   (clearing_reference IS NOT NULL AND clearing_reference <> NEW.reference)))
            THEN COALESCE(late_exception_at, clock_timestamp())
            ELSE late_exception_at
        END WHERE id = NEW.payment_id;
    RETURN NEW;
END;
$$;

CREATE TRIGGER clearing_evidence_revision AFTER INSERT ON payments.clearing_inbox
    FOR EACH ROW EXECUTE FUNCTION payments.revise_for_evidence();
