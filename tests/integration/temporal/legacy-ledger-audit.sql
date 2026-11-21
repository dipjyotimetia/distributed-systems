WITH cutoff AS (
    SELECT starts_at FROM accounts_service.posting_coverage
),
legacy AS (
    SELECT t.id, t.amount::text AS legacy_amount, d.amount AS exact_amount
    FROM transactions_service.transactions t
    LEFT JOIN transactions_service.movement_decisions d ON d.ledger_id = t.id
    CROSS JOIN cutoff
    WHERE t.status = 'COMPLETED' AND t.created_at AT TIME ZONE 'UTC' < cutoff.starts_at
),
opening AS (
    SELECT a.id, a.currency, a.balance - COALESCE(SUM(p.amount), 0) AS baseline
    FROM accounts_service.accounts a
    LEFT JOIN accounts_service.account_postings p ON p.account_id = a.id
    CROSS JOIN cutoff
    WHERE a.created_at AT TIME ZONE 'UTC' < cutoff.starts_at
    GROUP BY a.id
)
SELECT jsonb_build_object(
    'postingCoverageStart', (SELECT starts_at FROM cutoff),
    'legacyAmountMismatches', COALESCE((
        SELECT jsonb_agg(jsonb_build_object(
            'transactionId', id, 'legacyAmount', legacy_amount, 'exactMovementAmount', exact_amount::text
        ) ORDER BY id)
        FROM legacy WHERE exact_amount IS NOT NULL AND legacy_amount::numeric <> exact_amount
    ), '[]'::jsonb),
    'legacyTransactionsWithoutExactDecision', COALESCE((
        SELECT jsonb_agg(id ORDER BY id) FROM legacy WHERE exact_amount IS NULL
    ), '[]'::jsonb),
    'historicalNonzeroBaselinesForReview', COALESCE((
        SELECT jsonb_agg(jsonb_build_object('accountId', id, 'currency', currency, 'baseline', baseline::text) ORDER BY id)
        FROM opening WHERE baseline <> 0
    ), '[]'::jsonb)
);
