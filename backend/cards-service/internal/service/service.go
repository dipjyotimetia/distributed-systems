package service

import (
	"context"
	"crypto/rand"
	"fmt"
	"math/big"

	cardsv1 "github.com/dipjyotimetia/distributed-systems/backend/cards-service/gen/cards/v1"
	"github.com/dipjyotimetia/distributed-systems/backend/cards-service/internal/auth"
	"github.com/dipjyotimetia/distributed-systems/backend/cards-service/internal/ledger"
	fraudv1 "github.com/dipjyotimetia/distributed-systems/backend/fraud-service/gen/fraud/v1"
	kycv1 "github.com/dipjyotimetia/distributed-systems/backend/kyc-service/gen/kyc/v1"
	"github.com/google/uuid"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/metadata"
	"google.golang.org/grpc/status"
)

type Card struct {
	Token, LastFour string
	AccountID       int64
}

type Authorization struct {
	ID, CardToken, Key, Amount, Merchant, Reason string
	Approved                                     bool
}

type Repository interface {
	InsertCard(context.Context, int64, Card) error
	FindCard(context.Context, int64, string) (Card, error)
	FindAuthorization(context.Context, string, string) (Authorization, bool, error)
	SaveAuthorization(context.Context, Authorization) error
}

type Server struct {
	cardsv1.UnimplementedCardsServer
	Repo   Repository
	Secret []byte
	Ledger ledger.API
	Fraud  fraudv1.FraudCheckerClient
	Kyc    kycv1.KycClient
}

func (s *Server) IssueCard(ctx context.Context, request *cardsv1.IssueCardRequest) (*cardsv1.Card, error) {
	customer, authorization, err := auth.Customer(ctx, s.Secret)
	if err != nil {
		return nil, err
	}
	if request.GetAccountId() < 1 {
		return nil, status.Error(codes.InvalidArgument, "account is required")
	}
	if err := s.requireApproved(ctx, authorization); err != nil {
		return nil, err
	}
	if err := s.Ledger.Owns(ctx, authorization, request.GetAccountId()); err != nil {
		return nil, status.Error(codes.PermissionDenied, "account is not owned")
	}
	card := Card{Token: uuid.NewString(), AccountID: request.GetAccountId(), LastFour: lastFour()}
	if err := s.Repo.InsertCard(ctx, customer, card); err != nil {
		return nil, status.Error(codes.Internal, "could not issue the card")
	}
	return &cardsv1.Card{Token: card.Token, AccountId: card.AccountID, LastFour: card.LastFour}, nil
}

func (s *Server) Authorize(ctx context.Context, request *cardsv1.AuthorizeRequest) (*cardsv1.Authorization, error) {
	customer, _, err := auth.Customer(ctx, s.Secret)
	if err != nil {
		return nil, err
	}
	amount, err := ledger.CanonicalAmount(request.GetAmount())
	if err != nil || request.GetIdempotencyKey() == "" || request.GetMerchant() == "" {
		return nil, status.Error(codes.InvalidArgument, "amount, merchant, and idempotency key are required")
	}
	card, err := s.Repo.FindCard(ctx, customer, request.GetCardToken())
	if err != nil {
		return nil, status.Error(codes.NotFound, "card not found")
	}
	if existing, ok, err := s.Repo.FindAuthorization(ctx, card.Token, request.GetIdempotencyKey()); err != nil {
		return nil, status.Error(codes.Internal, "could not read the authorization")
	} else if ok {
		if existing.Amount != amount || existing.Merchant != request.GetMerchant() {
			return nil, status.Error(codes.FailedPrecondition, "idempotency key was reused")
		}
		return toProto(existing), nil
	}
	approved, reason := true, "approved"
	if request.GetMerchant() == "DECLINE" {
		approved, reason = false, "network declined"
	} else if s.Fraud != nil {
		response, err := s.Fraud.CheckTransaction(ctx, &fraudv1.TransactionRequest{
			AccountId: fmt.Sprint(card.AccountID), UserId: fmt.Sprint(customer), Amount: mustFloat(amount), TransactionType: "WITHDRAWAL", MerchantCategory: "5812",
		})
		if err != nil {
			return nil, status.Error(codes.Unavailable, "fraud screening unavailable")
		}
		if !response.GetApproved() {
			approved, reason = false, response.GetReason()
		}
	}
	item := Authorization{ID: uuid.NewString(), CardToken: card.Token, Key: request.GetIdempotencyKey(), Amount: amount, Merchant: request.GetMerchant(), Approved: approved, Reason: reason}
	if approved {
		if err := s.Ledger.Withdraw(ctx, customer, card.AccountID, amount, item.ID); err != nil {
			item.Approved, item.Reason = false, "insufficient funds"
		}
	}
	if err := s.Repo.SaveAuthorization(ctx, item); err != nil {
		return nil, status.Error(codes.Internal, "could not store the authorization")
	}
	return toProto(item), nil
}

func (s *Server) requireApproved(ctx context.Context, authorization string) error {
	if s.Kyc == nil {
		return status.Error(codes.Unavailable, "kyc unavailable")
	}
	call := metadata.AppendToOutgoingContext(ctx, "authorization", authorization)
	response, err := s.Kyc.GetStatus(call, &kycv1.GetStatusRequest{})
	if err != nil {
		return status.Error(codes.Unavailable, "kyc unavailable")
	}
	if response.GetStatus() != "APPROVED" {
		return status.Error(codes.PermissionDenied, "kyc approval is required")
	}
	return nil
}

func toProto(item Authorization) *cardsv1.Authorization {
	return &cardsv1.Authorization{Id: item.ID, CardToken: item.CardToken, Approved: item.Approved, Reason: item.Reason, Amount: item.Amount, Merchant: item.Merchant}
}

func lastFour() string {
	n, _ := rand.Int(rand.Reader, big.NewInt(10000))
	return fmt.Sprintf("%04d", n.Int64())
}

func mustFloat(amount string) float64 {
	value, _ := new(big.Rat).SetString(amount)
	number, _ := value.Float64()
	return number
}
