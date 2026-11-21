package httpapi

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"

	researchv1 "github.com/dipjyotimetia/distributed-systems/backend/disputes-service/gen/disputes/research/v1"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/metadata"
	"google.golang.org/grpc/status"
)

type ResearchServer struct {
	researchv1.UnimplementedDisputeResearchServer
	API API
}

func (s ResearchServer) GetContext(ctx context.Context, request *researchv1.GetContextRequest) (*researchv1.CaseJson, error) {
	item, err := s.API.ResearchContext(ctx, internalToken(ctx), request.GetCaseId())
	if err != nil {
		return nil, researchStatus(err)
	}
	body, err := json.Marshal(item)
	if err != nil {
		return nil, status.Error(codes.Internal, "Could not encode dispute")
	}
	return &researchv1.CaseJson{Json: string(body)}, nil
}

func (s ResearchServer) SaveDraft(ctx context.Context, request *researchv1.SaveDraftRequest) (*researchv1.CaseJson, error) {
	item, err := s.API.ResearchDraft(ctx, internalToken(ctx), request.GetCaseId(), json.RawMessage(request.GetDraftJson()))
	if err != nil {
		return nil, researchStatus(err)
	}
	body, err := json.Marshal(item)
	if err != nil {
		return nil, status.Error(codes.Internal, "Could not encode dispute")
	}
	return &researchv1.CaseJson{Json: string(body)}, nil
}

func internalToken(ctx context.Context) string {
	values := metadata.ValueFromIncomingContext(ctx, "x-internal-token")
	if len(values) == 0 {
		return ""
	}
	return values[0]
}

func researchStatus(err error) error {
	switch {
	case errors.Is(err, ErrInternalAuth):
		return status.Error(codes.Unauthenticated, "Internal token required")
	case errors.Is(err, sql.ErrNoRows):
		return status.Error(codes.NotFound, "Dispute not found")
	case err.Error() == "draft object is required":
		return status.Error(codes.InvalidArgument, err.Error())
	default:
		return status.Error(codes.Internal, "Could not access disputes")
	}
}
