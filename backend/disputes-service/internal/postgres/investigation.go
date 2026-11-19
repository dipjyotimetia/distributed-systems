package postgres

import (
	"context"
	"database/sql"
	"time"

	"github.com/speedscale/microsvc/disputes-service/internal/cases"
)

const signalCTE = `), queued AS (
	INSERT INTO disputes.signal_outbox SELECT id, revision FROM updated
	ON CONFLICT(case_id) DO UPDATE SET revision=excluded.revision
) SELECT ` + caseColumns + ` FROM updated`

func (s *Store) RequestEvidence(ctx context.Context, id, actor int64, key, prompt string) (cases.Case, error) {
	const update = `WITH updated AS (
		UPDATE disputes.cases SET revision=revision+1, evidence_deadline=statement_timestamp()+interval '168 hours',
			evidence=jsonb_build_object('id', id::text, 'prompt', $4::text, 'requestedBy', $2::bigint,
				'requestedAt', statement_timestamp(), 'deadline', statement_timestamp()+interval '168 hours', 'requestKey', $3::text)
		WHERE id=$1 AND escalation_deadline IS NOT NULL AND status<>'RESOLVED' AND evidence IS NULL RETURNING *
	` + signalCTE
	c, err := scanCase(s.db.QueryRowContext(ctx, update, id, actor, key, prompt))
	if err != sql.ErrNoRows {
		return c, err
	}
	return s.evidenceRetry(ctx, id, 0, "requestKey", key, "prompt", prompt, "requestedBy", actor)
}

func (s *Store) RespondToEvidence(ctx context.Context, id, customer int64, requestID, key, response string) (cases.Case, error) {
	const update = `WITH updated AS (
		UPDATE disputes.cases SET revision=revision+1, evidence_closed_at=clock_timestamp(),
			evidence=evidence || jsonb_build_object('responseKey', $4::text, 'response', $5::text,
				'respondedAt', clock_timestamp(), 'respondedBy', $2::bigint)
		WHERE id=$1 AND customer_id=$2 AND evidence->>'id'=$3 AND status<>'RESOLVED'
			AND evidence_closed_at IS NULL AND evidence_deadline>clock_timestamp() RETURNING *
	` + signalCTE
	// Lock before checking wall time so a concurrent command cannot delay the
	// response past its deadline while the UPDATE waits for a row lock.
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return cases.Case{}, err
	}
	defer tx.Rollback()
	var locked int64
	err = tx.QueryRowContext(ctx, `SELECT id FROM disputes.cases
		WHERE id=$1 AND customer_id=$2 AND evidence->>'id'=$3 FOR UPDATE`, id, customer, requestID).Scan(&locked)
	if err != nil {
		return cases.Case{}, err
	}
	c, err := scanCase(tx.QueryRowContext(ctx, update, id, customer, requestID, key, response))
	if err == sql.ErrNoRows {
		if err = tx.Rollback(); err != nil {
			return c, err
		}
		return s.evidenceRetry(ctx, id, customer, "responseKey", key, "response", response, "respondedBy", customer)
	}
	if err != nil {
		return c, err
	}
	return c, tx.Commit()
}

func (s *Store) evidenceRetry(ctx context.Context, id, customer int64, keyField, key, textField, value, actorField string, actor int64) (cases.Case, error) {
	const query = `SELECT ` + caseColumns + ` FROM disputes.cases WHERE id=$1 AND ($2=0 OR customer_id=$2)`
	c, err := scanCase(s.db.QueryRowContext(ctx, query+` AND evidence->>$3::text=$4 AND evidence->>$5::text=$6
		AND (evidence->>$7::text)::bigint=$8`, id, customer, keyField, key, textField, value, actorField, actor))
	if err != sql.ErrNoRows {
		return c, err
	}
	c, err = scanCase(s.db.QueryRowContext(ctx, query, id, customer))
	if err == nil {
		err = cases.ErrConflict
	}
	return c, err
}

// Advance applies due transitions and returns only scheduling metadata.
func (s *Store) Advance(ctx context.Context, id int64) (bool, *time.Time, error) {
	const query = `WITH updated AS (
		UPDATE disputes.cases SET
			status=CASE WHEN status='OPEN' AND escalation_deadline<=statement_timestamp() THEN 'ESCALATED' ELSE status END,
			escalated_at=CASE WHEN status='OPEN' AND escalation_deadline<=statement_timestamp() THEN statement_timestamp() ELSE escalated_at END,
			evidence_closed_at=CASE WHEN evidence_deadline<=statement_timestamp() THEN coalesce(evidence_closed_at,statement_timestamp()) ELSE evidence_closed_at END,
			revision=revision+1
		WHERE id=$1 AND status<>'RESOLVED' AND ((status='OPEN' AND escalation_deadline<=statement_timestamp())
			OR (evidence_closed_at IS NULL AND evidence_deadline<=statement_timestamp())) RETURNING *
	), current_case AS (
		SELECT * FROM updated UNION ALL SELECT * FROM disputes.cases WHERE id=$1 AND NOT EXISTS (SELECT FROM updated)
	)
	SELECT status='RESOLVED', LEAST(CASE WHEN status='OPEN' THEN escalation_deadline END,
		CASE WHEN evidence_closed_at IS NULL THEN evidence_deadline END) FROM current_case`
	var resolved bool
	var deadline *time.Time
	err := s.db.QueryRowContext(ctx, query, id).Scan(&resolved, &deadline)
	return resolved, deadline, err
}
