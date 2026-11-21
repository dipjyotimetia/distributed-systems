package service

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	cardsv1 "github.com/dipjyotimetia/distributed-systems/backend/cards-service/gen/cards/v1"
	"github.com/dipjyotimetia/distributed-systems/backend/cards-service/internal/ledger"
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
func (m *memory) ListCards(_ context.Context, customer int64) ([]Card, error) {
	cards := make([]Card, 0)
	for token, owner := range m.owner {
		if owner == customer {
			cards = append(cards, m.cards[token])
		}
	}
	return cards, nil
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

type fakeFraud struct{ approved bool }

func (f fakeFraud) CheckTransaction(context.Context, *fraudv1.TransactionRequest, ...grpc.CallOption) (*fraudv1.FraudCheckResponse, error) {
	return &fraudv1.FraudCheckResponse{Approved: f.approved, Reason: "rule"}, nil
}

func TestIssueRequiresKycAndAuthorizeIsIdempotent(t *testing.T) {
	accounts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method == http.MethodGet {
			w.WriteHeader(http.StatusOK)
			return
		}
		if r.Header.Get("X-Movement-Signature") == "" {
			t.Error("missing movement signature")
		}
		w.WriteHeader(http.StatusNoContent)
	}))
	defer accounts.Close()
	server := &Server{
		Repo:   &memory{cards: map[string]Card{}, auths: map[string]Authorization{}, owner: map[string]int64{}},
		Secret: []byte("secret"),
		Ledger: ledger.Client{Base: accounts.URL, Secret: "secret"},
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

func TestListCardsReturnsOnlyTheSignedInCustomer(t *testing.T) {
	repo := &memory{cards: map[string]Card{}, auths: map[string]Authorization{}, owner: map[string]int64{}}
	if err := repo.InsertCard(context.Background(), 7, Card{Token: "mine", AccountID: 4, LastFour: "1111"}); err != nil {
		t.Fatal(err)
	}
	if err := repo.InsertCard(context.Background(), 8, Card{Token: "theirs", AccountID: 9, LastFour: "2222"}); err != nil {
		t.Fatal(err)
	}
	server := &Server{Repo: repo, Secret: []byte("secret")}
	ctx := metadata.NewIncomingContext(context.Background(), metadata.Pairs("authorization", "Bearer "+token(t)))
	listed, err := server.ListCards(ctx, &cardsv1.ListCardsRequest{})
	if err != nil {
		t.Fatal(err)
	}
	if len(listed.Cards) != 1 || listed.Cards[0].GetToken() != "mine" || listed.Cards[0].GetLastFour() != "1111" {
		t.Fatalf("listed %+v", listed.Cards)
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
