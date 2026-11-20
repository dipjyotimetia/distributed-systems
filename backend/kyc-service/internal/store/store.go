package store

import (
	"context"
	"database/sql"
	"errors"

	"github.com/google/uuid"
	_ "github.com/jackc/pgx/v5/stdlib"
)

type Case struct {
	ID, FullName, DocumentReference, Status string
	CustomerID                              int64
}

type Store struct{ db *sql.DB }

func Open(url string) (*Store, error) {
	db, err := sql.Open("pgx", url)
	if err != nil {
		return nil, err
	}
	db.SetMaxOpenConns(8)
	return &Store{db: db}, nil
}

func (s *Store) Close() error { return s.db.Close() }

func (s *Store) Migrate(ctx context.Context) error {
	_, err := s.db.ExecContext(ctx, `
		CREATE SCHEMA IF NOT EXISTS kyc;
		CREATE TABLE IF NOT EXISTS kyc.cases (
			id UUID PRIMARY KEY,
			customer_id BIGINT NOT NULL,
			full_name TEXT NOT NULL,
			document_reference TEXT NOT NULL,
			status TEXT NOT NULL,
			created_at TIMESTAMPTZ NOT NULL DEFAULT now()
		);
		CREATE TABLE IF NOT EXISTS kyc.outbox (
			case_id UUID PRIMARY KEY REFERENCES kyc.cases(id),
			started_at TIMESTAMPTZ
		);
		CREATE TABLE IF NOT EXISTS kyc.decisions (
			case_id UUID PRIMARY KEY REFERENCES kyc.cases(id),
			approve BOOLEAN NOT NULL
		);`)
	return err
}

func (s *Store) Apply(ctx context.Context, customer int64, name, reference string) (Case, error) {
	latest, err := s.latest(ctx, customer)
	if err != nil {
		return Case{}, err
	}
	if latest.Status == "PENDING" || latest.Status == "APPROVED" {
		return latest, nil
	}
	item := Case{ID: uuid.NewString(), CustomerID: customer, FullName: name, DocumentReference: reference, Status: "PENDING"}
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return Case{}, err
	}
	defer tx.Rollback()
	if _, err = tx.ExecContext(ctx, `INSERT INTO kyc.cases (id, customer_id, full_name, document_reference, status) VALUES ($1,$2,$3,$4,'PENDING')`,
		item.ID, customer, name, reference); err != nil {
		return Case{}, err
	}
	if _, err = tx.ExecContext(ctx, `INSERT INTO kyc.outbox (case_id) VALUES ($1)`, item.ID); err != nil {
		return Case{}, err
	}
	return item, tx.Commit()
}

func (s *Store) Latest(ctx context.Context, customer int64) (Case, error) {
	item, err := s.latest(ctx, customer)
	if errors.Is(err, sql.ErrNoRows) {
		return Case{Status: "NONE"}, nil
	}
	return item, err
}

func (s *Store) latest(ctx context.Context, customer int64) (Case, error) {
	var item Case
	err := s.db.QueryRowContext(ctx, `SELECT id, customer_id, full_name, document_reference, status FROM kyc.cases
		WHERE customer_id=$1 ORDER BY created_at DESC LIMIT 1`, customer).
		Scan(&item.ID, &item.CustomerID, &item.FullName, &item.DocumentReference, &item.Status)
	if errors.Is(err, sql.ErrNoRows) {
		return Case{Status: "NONE"}, nil
	}
	return item, err
}

func (s *Store) Queue(ctx context.Context) ([]Case, error) {
	rows, err := s.db.QueryContext(ctx, `SELECT id, customer_id, full_name, document_reference, status FROM kyc.cases
		WHERE status='PENDING' AND upper(document_reference)='REVIEW' ORDER BY created_at LIMIT 50`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var items []Case
	for rows.Next() {
		var item Case
		if err := rows.Scan(&item.ID, &item.CustomerID, &item.FullName, &item.DocumentReference, &item.Status); err != nil {
			return nil, err
		}
		items = append(items, item)
	}
	return items, rows.Err()
}

func (s *Store) Reference(ctx context.Context, id string) (string, error) {
	var reference string
	err := s.db.QueryRowContext(ctx, `SELECT document_reference FROM kyc.cases WHERE id=$1`, id).Scan(&reference)
	return reference, err
}

func (s *Store) RecordDecision(ctx context.Context, id string, approve bool) (Case, error) {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return Case{}, err
	}
	defer tx.Rollback()
	status := "REJECTED"
	if approve {
		status = "APPROVED"
	}
	result, err := tx.ExecContext(ctx, `UPDATE kyc.cases SET status=$2 WHERE id=$1 AND status='PENDING'`, id, status)
	if err != nil {
		return Case{}, err
	}
	count, _ := result.RowsAffected()
	if count == 0 {
		return Case{}, sql.ErrNoRows
	}
	if _, err = tx.ExecContext(ctx, `INSERT INTO kyc.decisions (case_id, approve) VALUES ($1,$2) ON CONFLICT (case_id) DO NOTHING`, id, approve); err != nil {
		return Case{}, err
	}
	var item Case
	if err = tx.QueryRowContext(ctx, `SELECT id, customer_id, full_name, document_reference, status FROM kyc.cases WHERE id=$1`, id).
		Scan(&item.ID, &item.CustomerID, &item.FullName, &item.DocumentReference, &item.Status); err != nil {
		return Case{}, err
	}
	return item, tx.Commit()
}

func (s *Store) Decision(ctx context.Context, id string) (*bool, error) {
	var approve bool
	err := s.db.QueryRowContext(ctx, `SELECT approve FROM kyc.decisions WHERE case_id=$1`, id).Scan(&approve)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	return &approve, nil
}

func (s *Store) Complete(ctx context.Context, id, status string) error {
	_, err := s.db.ExecContext(ctx, `UPDATE kyc.cases SET status=$2 WHERE id=$1 AND status='PENDING'`, id, status)
	return err
}

func (s *Store) Claim(ctx context.Context) ([]string, error) {
	rows, err := s.db.QueryContext(ctx, `UPDATE kyc.outbox SET started_at=now()
		WHERE case_id IN (SELECT case_id FROM kyc.outbox WHERE started_at IS NULL ORDER BY case_id LIMIT 20)
		RETURNING case_id`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var ids []string
	for rows.Next() {
		var id string
		if err := rows.Scan(&id); err != nil {
			return nil, err
		}
		ids = append(ids, id)
	}
	return ids, rows.Err()
}
