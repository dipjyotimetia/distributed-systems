ALTER TABLE transactions_service.movement_decisions DROP CONSTRAINT movement_decisions_state_check;
ALTER TABLE transactions_service.movement_decisions ADD CONSTRAINT movement_decisions_state_check
    CHECK (state IN ('STARTED', 'COMMITTED', 'ABORTED', 'READY_TO_POST'));
ALTER TABLE transactions_service.transactions ADD COLUMN movement_posting BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX movement_decisions_unfinalized_ledger ON transactions_service.movement_decisions(ledger_id)
    WHERE NOT finalized AND ledger_id IS NOT NULL;
