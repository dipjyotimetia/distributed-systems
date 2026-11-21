ALTER TABLE transactions_service.transactions
    ALTER COLUMN amount TYPE NUMERIC(19,2) USING ROUND(amount::numeric, 2);

ALTER TABLE transactions_service.transactions
    ADD COLUMN amount_minor BIGINT;

CREATE OR REPLACE FUNCTION transactions_service.set_amount_minor()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.amount_minor IS NULL THEN
        NEW.amount_minor := ROUND(NEW.amount * 100)::bigint;
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS transactions_amount_minor ON transactions_service.transactions;
CREATE TRIGGER transactions_amount_minor
    BEFORE INSERT OR UPDATE OF amount, amount_minor
    ON transactions_service.transactions
    FOR EACH ROW
    EXECUTE FUNCTION transactions_service.set_amount_minor();

UPDATE transactions_service.transactions
    SET amount_minor = ROUND(amount * 100)::bigint
    WHERE amount_minor IS NULL;

ALTER TABLE transactions_service.transactions
    ALTER COLUMN amount_minor SET NOT NULL;

ALTER TABLE transactions_service.transactions
    DROP CONSTRAINT IF EXISTS chk_amount_positive;

ALTER TABLE transactions_service.transactions
    ADD CONSTRAINT chk_amount_positive CHECK (amount > 0);

ALTER TABLE transactions_service.transactions
    ADD CONSTRAINT chk_amount_minor CHECK (amount_minor = ROUND(amount * 100)::bigint AND amount_minor > 0);
