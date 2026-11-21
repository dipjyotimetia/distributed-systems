package store

import (
	"context"
	"database/sql"
	"errors"

	"github.com/dipjyotimetia/distributed-systems/backend/cards-service/internal/service"
	_ "github.com/jackc/pgx/v5/stdlib"
)

type Store struct{ db *sql.DB }

func Open(url string) (*Store, error) {
	db, err := sql.Open("pgx", url)
	if err != nil {
		return nil, err
	}
	return &Store{db: db}, nil
}

func (s *Store) Close() error { return s.db.Close() }

func (s *Store) Migrate(ctx context.Context) error {
	_, err := s.db.ExecContext(ctx, `
		CREATE SCHEMA IF NOT EXISTS cards;
		CREATE TABLE IF NOT EXISTS cards.cards (
			token UUID PRIMARY KEY,
			customer_id BIGINT NOT NULL,
			account_id BIGINT NOT NULL,
			last_four CHAR(4) NOT NULL,
			created_at TIMESTAMPTZ NOT NULL DEFAULT now()
		);
		CREATE TABLE IF NOT EXISTS cards.authorizations (
			id UUID PRIMARY KEY,
			card_token UUID NOT NULL,
			idempotency_key TEXT NOT NULL,
			amount TEXT NOT NULL,
			merchant TEXT NOT NULL,
			approved BOOLEAN NOT NULL,
			reason TEXT NOT NULL,
			UNIQUE (card_token, idempotency_key)
		);`)
	return err
}

func (s *Store) InsertCard(ctx context.Context, customer int64, card service.Card) error {
	_, err := s.db.ExecContext(ctx, `INSERT INTO cards.cards (token, customer_id, account_id, last_four) VALUES ($1,$2,$3,$4)`,
		card.Token, customer, card.AccountID, card.LastFour)
	return err
}

func (s *Store) ListCards(ctx context.Context, customer int64) ([]service.Card, error) {
	rows, err := s.db.QueryContext(ctx, `SELECT token, account_id, last_four FROM cards.cards WHERE customer_id=$1 ORDER BY created_at DESC`, customer)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	cards := make([]service.Card, 0)
	for rows.Next() {
		var card service.Card
		if err := rows.Scan(&card.Token, &card.AccountID, &card.LastFour); err != nil {
			return nil, err
		}
		cards = append(cards, card)
	}
	return cards, rows.Err()
}

func (s *Store) FindCard(ctx context.Context, customer int64, token string) (service.Card, error) {
	var card service.Card
	err := s.db.QueryRowContext(ctx, `SELECT token, account_id, last_four FROM cards.cards WHERE token=$1 AND customer_id=$2`, token, customer).
		Scan(&card.Token, &card.AccountID, &card.LastFour)
	return card, err
}

func (s *Store) FindAuthorization(ctx context.Context, token, key string) (service.Authorization, bool, error) {
	var item service.Authorization
	err := s.db.QueryRowContext(ctx, `SELECT id, card_token, amount, merchant, approved, reason FROM cards.authorizations WHERE card_token=$1 AND idempotency_key=$2`, token, key).
		Scan(&item.ID, &item.CardToken, &item.Amount, &item.Merchant, &item.Approved, &item.Reason)
	if errors.Is(err, sql.ErrNoRows) {
		return service.Authorization{}, false, nil
	}
	return item, err == nil, err
}

func (s *Store) SaveAuthorization(ctx context.Context, item service.Authorization) error {
	_, err := s.db.ExecContext(ctx, `INSERT INTO cards.authorizations (id, card_token, idempotency_key, amount, merchant, approved, reason)
		VALUES ($1,$2,$3,$4,$5,$6,$7)`, item.ID, item.CardToken, item.Key, item.Amount, item.Merchant, item.Approved, item.Reason)
	return err
}
