package service

import (
	"context"
	"testing"
	"time"

	cardsv1 "github.com/dipjyotimetia/distributed-systems/backend/cards-service/gen/cards/v1"
	fraudv1 "github.com/dipjyotimetia/distributed-systems/backend/fraud-service/gen/fraud/v1"
	kycv1 "github.com/dipjyotimetia/distributed-systems/backend/kyc-service/gen/kyc/v1"
	"github.com/golang-jwt/jwt/v5"
	"google.golang.org/grpc"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/metadata"
	"google.golang.org/grpc/status"
)

type memory struct {
	cards map[string]Card
	auths map[string]Authorization
	owner map[string]int64
}

func (m *memory) InsertCard(_ context.Context, customer int64, card Card) error {
	m.cards[card.Token] = card
	m.owner[card.Token] = customer
	return nil
}
func (m *memory) FindCard(_ context.Context, customer int64, token string) (Card, error) {
	if m.owner[token] != customer {
		return Card{}, status.Error(codes.NotFound, "missing")
	}
	return m.cards[token], nil
}
func (m *memory) FindAuthorization(_ context.Context, token, key string) (Authorization, bool, error) {
	item, ok := m.auths[token+"/"+key]
	return item, ok, nil
}
func (m *memory) SaveAuthorization(_ context.Context, item Authorization) error {
	m.auths[item.CardToken+"/"+item.Key] = item
	return nil
}

type fakeKyc struct{ status string }

func (f fakeKyc) Apply(context.Context, *kycv1.ApplyRequest, ...grpc.CallOption) (*kycv1.Case, error) {
	return nil, nil
}
func (f fakeKyc) GetStatus(context.Context, *kycv1.GetStatusRequest, ...grpc.CallOption) (*kycv1.Status, error) {
	return &kycv1.Status{Status: f.status}, nil
}
func (f fakeKyc) Queue(context.Context, *kycv1.QueueRequest, ...grpc.CallOption) (*kycv1.QueueResponse, error) {
	return &kycv1.QueueResponse{}, nil
}
func (f fakeKyc) Decide(context.Context, *kycv1.DecideRequest, ...grpc.CallOption) (*kycv1.Case, error) {
	return nil, nil
}

type fakeLedger struct{}

func (fakeLedger) Owns(context.Context, string, int64) error { return nil }
func (fakeLedger) Withdraw(context.Context, int64, int64, string, string) error {
	return nil
}

type fakeFraud struct{ approved bool }

func (f fakeFraud) CheckTransaction(context.Context, *fraudv1.TransactionRequest, ...grpc.CallOption) (*fraudv1.FraudCheckResponse, error) {
	return &fraudv1.FraudCheckResponse{Approved: f.approved, Reason: "rule"}, nil
}

func TestIssueRequiresKycAndAuthorizeIsIdempotent(t *testing.T) {
	server := &Server{
		Repo:   &memory{cards: map[string]Card{}, auths: map[string]Authorization{}, owner: map[string]int64{}},
		Secret: []byte("secret"),
		Ledger: fakeLedger{},
		Kyc:    fakeKyc{status: "PENDING"}, Fraud: fakeFraud{approved: true},
	}
	ctx := metadata.NewIncomingContext(context.Background(), metadata.Pairs("authorization", "Bearer "+token(t)))
	if _, err := server.IssueCard(ctx, &cardsv1.IssueCardRequest{AccountId: 4}); status.Code(err) != codes.PermissionDenied {
		t.Fatal(err)
	}
	server.Kyc = fakeKyc{status: "APPROVED"}
	card, err := server.IssueCard(ctx, &cardsv1.IssueCardRequest{AccountId: 4})
	if err != nil {
		t.Fatal(err)
	}
	first, err := server.Authorize(ctx, &cardsv1.AuthorizeRequest{CardToken: card.Token, IdempotencyKey: "retry", Amount: "10.50", Merchant: "Cafe"})
	if err != nil || !first.Approved {
		t.Fatal(err, first)
	}
	second, err := server.Authorize(ctx, &cardsv1.AuthorizeRequest{CardToken: card.Token, IdempotencyKey: "retry", Amount: "10.50", Merchant: "Cafe"})
	if err != nil || second.Id != first.Id {
		t.Fatal(err, second)
	}
	declined, err := server.Authorize(ctx, &cardsv1.AuthorizeRequest{CardToken: card.Token, IdempotencyKey: "other", Amount: "3.00", Merchant: "DECLINE"})
	if err != nil || declined.Approved {
		t.Fatal(err, declined)
	}
}

func token(t *testing.T) string {
	t.Helper()
	signed, err := jwt.NewWithClaims(jwt.SigningMethodHS256, jwt.MapClaims{"userId": "7", "exp": time.Now().Add(time.Hour).Unix()}).SignedString([]byte("secret"))
	if err != nil {
		t.Fatal(err)
	}
	return signed
}
