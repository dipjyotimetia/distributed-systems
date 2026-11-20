package rpc

import (
	"context"
	"database/sql"
	"errors"
	"strings"

	"github.com/dipjyotimetia/distributed-systems/backend/kyc-service/gen/kyc/v1"
	"github.com/dipjyotimetia/distributed-systems/backend/kyc-service/internal/auth"
	"github.com/dipjyotimetia/distributed-systems/backend/kyc-service/internal/store"
	sdk "go.temporal.io/sdk/client"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
)

type Server struct {
	kycv1.UnimplementedKycServer
	Store     *store.Store
	Secret    []byte
	Operators map[int64]bool
	Temporal  sdk.Client
}

func (s *Server) Apply(ctx context.Context, request *kycv1.ApplyRequest) (*kycv1.Case, error) {
	customer, err := auth.Customer(ctx, s.Secret)
	if err != nil {
		return nil, err
	}
	name := strings.TrimSpace(request.GetFullName())
	reference := strings.TrimSpace(request.GetDocumentReference())
	if name == "" || len(name) > 200 || reference == "" || len(reference) > 64 {
		return nil, status.Error(codes.InvalidArgument, "name and document reference are required")
	}
	item, err := s.Store.Apply(ctx, customer, name, reference)
	if err != nil {
		return nil, status.Error(codes.Internal, "could not store the application")
	}
	return toProto(item), nil
}

func (s *Server) GetStatus(ctx context.Context, _ *kycv1.GetStatusRequest) (*kycv1.Status, error) {
	customer, err := auth.Customer(ctx, s.Secret)
	if err != nil {
		return nil, err
	}
	item, err := s.Store.Latest(ctx, customer)
	if err != nil {
		return nil, status.Error(codes.Internal, "could not read the application")
	}
	response := &kycv1.Status{Status: item.Status}
	if item.ID != "" {
		response.Latest = toProto(item)
	}
	return response, nil
}

func (s *Server) Queue(ctx context.Context, _ *kycv1.QueueRequest) (*kycv1.QueueResponse, error) {
	if err := s.operator(ctx); err != nil {
		return nil, err
	}
	items, err := s.Store.Queue(ctx)
	if err != nil {
		return nil, status.Error(codes.Internal, "could not read the queue")
	}
	response := &kycv1.QueueResponse{}
	for _, item := range items {
		response.Cases = append(response.Cases, toProto(item))
	}
	return response, nil
}

func (s *Server) Decide(ctx context.Context, request *kycv1.DecideRequest) (*kycv1.Case, error) {
	if err := s.operator(ctx); err != nil {
		return nil, err
	}
	item, err := s.Store.RecordDecision(ctx, request.GetCaseId(), request.GetApprove())
	if errors.Is(err, sql.ErrNoRows) {
		return nil, status.Error(codes.FailedPrecondition, "case is not waiting for review")
	}
	if err != nil {
		return nil, status.Error(codes.Internal, "could not record the decision")
	}
	if s.Temporal != nil {
		_ = s.Temporal.SignalWorkflow(ctx, request.GetCaseId(), "", "kycDecisionV1", request.GetApprove())
	}
	return toProto(item), nil
}

func (s *Server) operator(ctx context.Context) error {
	customer, err := auth.Customer(ctx, s.Secret)
	if err != nil {
		return err
	}
	if !s.Operators[customer] {
		return status.Error(codes.PermissionDenied, "operations access is required")
	}
	return nil
}

func toProto(item store.Case) *kycv1.Case {
	return &kycv1.Case{Id: item.ID, CustomerId: item.CustomerID, FullName: item.FullName, DocumentReference: item.DocumentReference, Status: item.Status}
}
