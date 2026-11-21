package fraud

import (
	"context"
	"testing"

	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/metadata"
	"google.golang.org/grpc/status"
)

func TestAuthorizeRequiresMatchingToken(t *testing.T) {
	if err := Authorize(context.Background(), "secret"); status.Code(err) != codes.Unauthenticated {
		t.Fatalf("missing metadata: %v", err)
	}
	ctx := metadata.NewIncomingContext(context.Background(), metadata.Pairs("x-fraud-token", "other"))
	if err := Authorize(ctx, "secret"); status.Code(err) != codes.Unauthenticated {
		t.Fatalf("wrong token: %v", err)
	}
	ctx = metadata.NewIncomingContext(context.Background(), metadata.Pairs("x-fraud-token", "secret"))
	if err := Authorize(ctx, "secret"); err != nil {
		t.Fatal(err)
	}
}
