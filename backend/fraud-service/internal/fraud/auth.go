package fraud

import (
	"context"
	"crypto/subtle"

	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/metadata"
	"google.golang.org/grpc/status"
)

const tokenHeader = "x-fraud-token"

// Authorize requires the shared client token on every fraud RPC.
func Authorize(ctx context.Context, secret string) error {
	if secret == "" {
		return status.Error(codes.Unauthenticated, "fraud client token is required")
	}
	md, ok := metadata.FromIncomingContext(ctx)
	if !ok {
		return status.Error(codes.Unauthenticated, "fraud client token is required")
	}
	values := md.Get(tokenHeader)
	if len(values) != 1 || subtle.ConstantTimeCompare([]byte(values[0]), []byte(secret)) != 1 {
		return status.Error(codes.Unauthenticated, "fraud client token is required")
	}
	return nil
}
