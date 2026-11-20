package store

import (
	"context"
	"os"
	"testing"
)

func TestApplyAndDecision(t *testing.T) {
	url := os.Getenv("KYC_TEST_DATABASE_URL")
	if url == "" {
		t.Skip("KYC_TEST_DATABASE_URL is not set")
	}
	ctx := context.Background()
	db, err := Open(url)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { db.Close() })
	if err := db.Migrate(ctx); err != nil {
		t.Fatal(err)
	}
	if _, err = db.db.ExecContext(ctx, `DELETE FROM kyc.decisions; DELETE FROM kyc.outbox; DELETE FROM kyc.cases`); err != nil {
		t.Fatal(err)
	}
	first, err := db.Apply(ctx, 42, "Ada", "REVIEW")
	if err != nil || first.Status != "PENDING" {
		t.Fatal(err, first)
	}
	again, err := db.Apply(ctx, 42, "Ada", "REVIEW")
	if err != nil || again.ID != first.ID {
		t.Fatal(err, again.ID, first.ID)
	}
	decided, err := db.RecordDecision(ctx, first.ID, true)
	if err != nil || decided.Status != "APPROVED" {
		t.Fatal(err, decided.Status)
	}
	latest, err := db.Latest(ctx, 42)
	if err != nil || latest.Status != "APPROVED" {
		t.Fatal(err, latest.Status)
	}
}
