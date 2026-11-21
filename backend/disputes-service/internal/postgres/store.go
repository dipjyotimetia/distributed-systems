package postgres

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"time"

	"github.com/dipjyotimetia/distributed-systems/backend/disputes-service/internal/cases"
)

const caseColumns = "id, transfer_id, reason, status, created_at, escalated_at, resolved_at, resolved_by, resolution, evidence - 'requestKey' - 'responseKey', evidence_closed_at, escalation_deadline IS NOT NULL, ai_draft"
const selectByTransfer = "SELECT " + caseColumns + " FROM disputes.cases WHERE customer_id=$1 AND transfer_id=$2"

type Store struct {
	db *sql.DB
}

func scanCase(row interface{ Scan(...any) error }) (cases.Case, error) {
	var c cases.Case
	var evidence []byte
	var closed *time.Time
	var aiDraft []byte
	err := row.Scan(&c.ID, &c.TransferID, &c.Reason, &c.Status, &c.CreatedAt,
		&c.EscalatedAt, &c.ResolvedAt, &c.ResolvedBy, &c.Resolution, &evidence, &closed, &c.EvidenceEnabled, &aiDraft)
	if err == nil && len(evidence) != 0 {
		err = json.Unmarshal(evidence, &c.Evidence)
		if err == nil {
			c.Evidence.ClosedAt = closed
		}
	}
	if err == nil && len(aiDraft) != 0 {
		var draft any
		if unmarshalErr := json.Unmarshal(aiDraft, &draft); unmarshalErr == nil {
			c.AiDraft = draft
		} else {
			err = unmarshalErr
		}
	}
	c.InvestigationStage = c.Stage()
	return c, err
}

func (s *Store) ByTransfer(ctx context.Context, customer, transfer int64) (cases.Case, error) {
	return scanCase(s.db.QueryRowContext(ctx, selectByTransfer, customer, transfer))
}

func (s *Store) ByID(ctx context.Context, customer, id int64) (cases.Case, error) {
	return scanCase(s.db.QueryRowContext(ctx, "SELECT "+caseColumns+" FROM disputes.cases WHERE customer_id=$1 AND id=$2", customer, id))
}

func (s *Store) Create(ctx context.Context, customer, transfer int64, reason string, delay time.Duration) (cases.Case, bool, error) {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return cases.Case{}, false, err
	}
	defer tx.Rollback()
	c, err := scanCase(tx.QueryRowContext(ctx, `INSERT INTO disputes.cases (customer_id, transfer_id, reason, escalation_deadline)
		VALUES ($1,$2,$3,now()+$4*interval '1 microsecond') ON CONFLICT (transfer_id) DO NOTHING RETURNING `+caseColumns, customer, transfer, reason, delay.Microseconds()))
	if errors.Is(err, sql.ErrNoRows) {
		c, err = scanCase(tx.QueryRowContext(ctx, selectByTransfer, customer, transfer))
		return c, false, err
	}
	if err != nil {
		return c, false, err
	}
	_, err = tx.ExecContext(ctx, "INSERT INTO disputes.workflow_outbox (case_id, deadline) VALUES ($1,$2)", c.ID, c.CreatedAt.Add(delay))
	if err != nil {
		return c, false, err
	}
	return c, true, tx.Commit()
}

func (s *Store) CustomerCases(ctx context.Context, customer int64) ([]cases.Case, error) {
	return s.list(ctx, "WHERE customer_id=$1 ORDER BY id DESC", customer)
}

func (s *Store) OperationsQueue(ctx context.Context, limit, offset int64) ([]cases.Case, error) {
	return s.list(ctx, `WHERE status IN ('OPEN','ESCALATED')
   ORDER BY status, id LIMIT $1 OFFSET $2`, limit, offset)
}

func (s *Store) list(ctx context.Context, query string, args ...any) ([]cases.Case, error) {
	rows, err := s.db.QueryContext(ctx, "SELECT "+caseColumns+" FROM disputes.cases "+query, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	items := []cases.Case{}
	for rows.Next() {
		c, err := scanCase(rows)
		if err != nil {
			return nil, err
		}
		items = append(items, c)
	}
	return items, rows.Err()
}

func (s *Store) Resolve(ctx context.Context, id, operator int64, summary string) (cases.Case, error) {
	c, err := scanCase(s.db.QueryRowContext(ctx, `WITH updated AS (
  UPDATE disputes.cases SET status='RESOLVED', resolved_at=now(), resolved_by=$2,
    resolution=$3, revision=revision+1,
    evidence_closed_at=CASE WHEN evidence IS NOT NULL THEN coalesce(evidence_closed_at,clock_timestamp()) END
  WHERE id=$1 AND status IN ('OPEN','ESCALATED') RETURNING *
 ), queued AS (
  INSERT INTO disputes.signal_outbox SELECT id, revision FROM updated WHERE escalation_deadline IS NOT NULL
  ON CONFLICT(case_id) DO UPDATE SET revision=excluded.revision
 ) SELECT `+caseColumns+` FROM updated`, id, operator, summary))
	if !errors.Is(err, sql.ErrNoRows) {
		return c, err
	}
	c, err = scanCase(s.db.QueryRowContext(ctx, "SELECT "+caseColumns+" FROM disputes.cases WHERE id=$1", id))
	if err == nil && (c.Resolution == nil || *c.Resolution != summary) {
		return c, cases.ErrConflict
	}
	return c, err
}

func (s *Store) Escalate(ctx context.Context, id int64) error {
	_, err := s.db.ExecContext(ctx, "UPDATE disputes.cases SET status='ESCALATED', escalated_at=now() WHERE id=$1 AND status='OPEN'", id)
	return err
}

// SaveAiDraft stores an operator-facing AI research draft and signals the investigation workflow.
func (s *Store) SaveAiDraft(ctx context.Context, id int64, draft json.RawMessage) (cases.Case, error) {
	c, err := scanCase(s.db.QueryRowContext(ctx, `WITH updated AS (
  UPDATE disputes.cases SET ai_draft=$2::jsonb, revision=revision+1
  WHERE id=$1 AND status<>'RESOLVED' RETURNING *
 ), queued AS (
  INSERT INTO disputes.signal_outbox SELECT id, revision FROM updated WHERE escalation_deadline IS NOT NULL
  ON CONFLICT(case_id) DO UPDATE SET revision=excluded.revision
 ) SELECT `+caseColumns+` FROM updated`, id, draft))
	if !errors.Is(err, sql.ErrNoRows) {
		return c, err
	}
	return scanCase(s.db.QueryRowContext(ctx, "SELECT "+caseColumns+" FROM disputes.cases WHERE id=$1", id))
}

// ByIDInternal loads a case without customer scoping (internal AI worker).
func (s *Store) ByIDInternal(ctx context.Context, id int64) (cases.Case, error) {
	return scanCase(s.db.QueryRowContext(ctx, "SELECT "+caseColumns+" FROM disputes.cases WHERE id=$1", id))
}

// NeedsAiDraft reports whether research should be started for the case.
func (s *Store) NeedsAiDraft(ctx context.Context, id int64) (bool, error) {
	var needed bool
	err := s.db.QueryRowContext(ctx, `SELECT status<>'RESOLVED' AND ai_draft IS NULL
		AND (status='ESCALATED' OR evidence_closed_at IS NOT NULL OR
			(evidence IS NULL AND escalation_deadline IS NOT NULL AND status='OPEN'))
		FROM disputes.cases WHERE id=$1`, id).Scan(&needed)
	return needed, err
}

func New(db *sql.DB) *Store { return &Store{db: db} }
