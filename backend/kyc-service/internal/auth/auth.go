package auth

import (
	"context"
	"errors"
	"strconv"
	"strings"

	"github.com/golang-jwt/jwt/v5"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/metadata"
	"google.golang.org/grpc/status"
)

type claims struct {
	UserID string `json:"userId"`
	jwt.RegisteredClaims
}

func Customer(ctx context.Context, secret []byte) (int64, error) {
	raw, err := bearer(ctx)
	if err != nil {
		return 0, status.Error(codes.Unauthenticated, "sign in to continue")
	}
	var identity claims
	_, err = jwt.ParseWithClaims(raw, &identity, func(*jwt.Token) (any, error) {
		return secret, nil
	}, jwt.WithValidMethods([]string{"HS256"}), jwt.WithExpirationRequired())
	if err != nil {
		return 0, status.Error(codes.Unauthenticated, "sign in to continue")
	}
	id, err := strconv.ParseInt(identity.UserID, 10, 64)
	if err != nil || id < 1 {
		return 0, status.Error(codes.Unauthenticated, "sign in to continue")
	}
	return id, nil
}

func bearer(ctx context.Context) (string, error) {
	md, ok := metadata.FromIncomingContext(ctx)
	if !ok {
		return "", errors.New("missing metadata")
	}
	values := md.Get("authorization")
	if len(values) == 0 {
		return "", errors.New("missing authorization")
	}
	scheme, token, ok := strings.Cut(values[0], " ")
	if !ok || !strings.EqualFold(scheme, "Bearer") || token == "" {
		return "", errors.New("bearer token required")
	}
	return token, nil
}
