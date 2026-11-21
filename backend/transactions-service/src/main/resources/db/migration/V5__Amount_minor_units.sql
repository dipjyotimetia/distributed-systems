ALTER TABLE transactions_service.transactions
    ALTER COLUMN amount TYPE NUMERIC(19,2) USING ROUND(amount::numeric, 2);

ALTER TABLE transactions_service.transactions
    ADD COLUMN amount_minor BIGINT;

UPDATE transactions_service.transactions
    SET amount_minor = ROUND(amount * 100)::bigint;

ALTER TABLE transactions_service.transactions
    ALTER COLUMN amount_minor SET NOT NULL;

ALTER TABLE transactions_service.transactions
    DROP CONSTRAINT IF EXISTS chk_amount_positive;

ALTER TABLE transactions_service.transactions
    ADD CONSTRAINT chk_amount_positive CHECK (amount > 0);

ALTER TABLE transactions_service.transactions
    ADD CONSTRAINT chk_amount_minor CHECK (amount_minor = ROUND(amount * 100)::bigint AND amount_minor > 0);
