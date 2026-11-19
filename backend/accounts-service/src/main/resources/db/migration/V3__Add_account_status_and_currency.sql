ALTER TABLE accounts_service.accounts
    ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN currency VARCHAR(3) NOT NULL DEFAULT 'USD',
    ADD CONSTRAINT chk_account_status CHECK (status IN ('ACTIVE', 'INACTIVE', 'FROZEN', 'CLOSED')),
    ADD CONSTRAINT chk_account_currency CHECK (currency ~ '^[A-Z]{3}$');

ALTER TABLE accounts_service.accounts DROP CONSTRAINT chk_account_type;
ALTER TABLE accounts_service.accounts
    ADD CONSTRAINT chk_account_type CHECK (account_type IN ('CHECKING', 'SAVINGS', 'CREDIT', 'INVESTMENT'));
