package httpapi

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"testing"
	"time"

	researchv1 "github.com/dipjyotimetia/distributed-systems/backend/disputes-service/gen/disputes/research/v1"
	"github.com/dipjyotimetia/distributed-systems/backend/disputes-service/internal/cases"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/metadata"
	"google.golang.org/grpc/status"
)

type researchStore struct {
	item cases.Case
	err  error
}

func (s researchStore) ByTransfer(context.Context, int64, int64) (cases.Case, error) {
	return cases.Case{}, s.err
}
func (s researchStore) ByID(context.Context, int64, int64) (cases.Case, error) {
	return s.item, s.err
}
func (s researchStore) ByIDInternal(context.Context, int64) (cases.Case, error) {
	return s.item, s.err
}
func (s researchStore) Create(context.Context, int64, int64, string, time.Duration) (cases.Case, bool, error) {
	return s.item, false, s.err
}
func (s researchStore) CustomerCases(context.Context, int64) ([]cases.Case, error) {
	return nil, s.err
}
func (s researchStore) OperationsQueue(context.Context, int64, int64) ([]cases.Case, error) {
	return nil, s.err
}
func (s researchStore) Resolve(context.Context, int64, int64, string) (cases.Case, error) {
	return s.item, s.err
}
func (s researchStore) RequestEvidence(context.Context, int64, int64, string, string) (cases.Case, error) {
	return s.item, s.err
}
func (s researchStore) RespondToEvidence(context.Context, int64, int64, string, string, string) (cases.Case, error) {
	return s.item, s.err
}
func (s researchStore) SaveAiDraft(context.Context, int64, json.RawMessage) (cases.Case, error) {
	return s.item, s.err
}

func TestResearchGRPC(t *testing.T) {
	server := ResearchServer{API: NewWithInternal(nil, researchStore{item: cases.Case{ID: 9, Status: "ESCALATED"}}, nil, nil, nil, 0, "secret")}
	if _, err := server.GetContext(context.Background(), &researchv1.GetContextRequest{CaseId: 9}); status.Code(err) != codes.Unauthenticated {
		t.Fatalf("missing token: %v", err)
	}
	denied := metadata.NewIncomingContext(context.Background(), metadata.Pairs("x-internal-token", "wrong"))
	if _, err := server.SaveDraft(denied, &researchv1.SaveDraftRequest{CaseId: 9}); status.Code(err) != codes.Unauthenticated {
		t.Fatalf("wrong token: %v", err)
	}
	ctx := metadata.NewIncomingContext(context.Background(), metadata.Pairs("x-internal-token", "secret"))
	loaded, err := server.GetContext(ctx, &researchv1.GetContextRequest{CaseId: 9})
	if err != nil || !json.Valid([]byte(loaded.GetJson())) {
		t.Fatalf("context: %+v %v", loaded, err)
	}
	saved, err := server.SaveDraft(ctx, &researchv1.SaveDraftRequest{
		CaseId:    9,
		DraftJson: `{"reasonCode":"UNAUTHORIZED","summary":"Looks fraudulent","recommendedAction":"REQUEST_EVIDENCE","confidence":0.9}`,
	})
	if err != nil || !json.Valid([]byte(saved.GetJson())) {
		t.Fatalf("draft: %+v %v", saved, err)
	}
	if _, err := server.SaveDraft(ctx, &researchv1.SaveDraftRequest{CaseId: 9, DraftJson: `{}`}); status.Code(err) != codes.InvalidArgument {
		t.Fatalf("empty draft: %v", err)
	}

	missing := ResearchServer{API: NewWithInternal(nil, researchStore{err: sql.ErrNoRows}, nil, nil, nil, 0, "secret")}
	if _, err := missing.GetContext(ctx, &researchv1.GetContextRequest{CaseId: 9}); status.Code(err) != codes.NotFound {
		t.Fatalf("missing case: %v", err)
	}
	broken := ResearchServer{API: NewWithInternal(nil, researchStore{err: errors.New("db down")}, nil, nil, nil, 0, "secret")}
	if _, err := broken.SaveDraft(ctx, &researchv1.SaveDraftRequest{CaseId: 9, DraftJson: `{"reasonCode":"UNAUTHORIZED","summary":"Looks fraudulent","recommendedAction":"REQUEST_EVIDENCE","confidence":0.9}`}); status.Code(err) != codes.Internal {
		t.Fatalf("store error: %v", err)
	}
	unencodable := ResearchServer{API: NewWithInternal(nil, researchStore{item: cases.Case{AiDraft: make(chan int)}}, nil, nil, nil, 0, "secret")}
	if _, err := unencodable.GetContext(ctx, &researchv1.GetContextRequest{CaseId: 9}); status.Code(err) != codes.Internal {
		t.Fatalf("encode context: %v", err)
	}
	if _, err := unencodable.SaveDraft(ctx, &researchv1.SaveDraftRequest{CaseId: 9, DraftJson: `{"reasonCode":"UNAUTHORIZED","summary":"Looks fraudulent","recommendedAction":"REQUEST_EVIDENCE","confidence":0.9}`}); status.Code(err) != codes.Internal {
		t.Fatalf("encode draft: %v", err)
	}
}
