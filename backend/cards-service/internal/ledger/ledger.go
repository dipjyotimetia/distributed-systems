package ledger

import (
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"math/big"
	"strings"

	ledgerv1 "github.com/dipjyotimetia/distributed-systems/backend/cards-service/gen/ledger/v1"
	"google.golang.org/grpc/metadata"
)

type API interface {
	Owns(ctx context.Context, authorization string, account int64) error
	Withdraw(ctx context.Context, user, account int64, amount, operation string) error
}

func CanonicalAmount(raw string) (string, error) {
	value, ok := new(big.Rat).SetString(strings.TrimSpace(raw))
	if !ok || value.Sign() <= 0 {
		return "", fmt.Errorf("invalid amount")
	}
	return value.FloatString(2), nil
}

func Sign(secret, operation, action string, user, from int64, amount string) string {
	mac := hmac.New(sha256.New, []byte(secret))
	fmt.Fprintf(mac, "account-movement:v1:%s:%s:%d:%d:null:%s", operation, action, user, from, amount)
	return hex.EncodeToString(mac.Sum(nil))
}

type Client struct {
	Ledger ledgerv1.AccountLedgerClient
	Secret string
}

func (c Client) Owns(ctx context.Context, authorization string, account int64) error {
	ctx = metadata.AppendToOutgoingContext(ctx, "authorization", authorization)
	_, err := c.Ledger.GetAccount(ctx, &ledgerv1.GetAccountRequest{AccountId: account})
	if err != nil {
		return fmt.Errorf("account %d is not owned", account)
	}
	return nil
}

func (c Client) Withdraw(ctx context.Context, user, account int64, amount, operation string) error {
	if err := c.action(ctx, "RESERVE", user, account, amount, operation); err != nil {
		return err
	}
	if err := c.action(ctx, "COMMIT", user, account, amount, operation); err != nil {
		_ = c.action(ctx, "COMPENSATE", user, account, amount, operation)
		return err
	}
	return nil
}

func (c Client) action(ctx context.Context, action string, user, account int64, amount, operation string) error {
	ctx = metadata.AppendToOutgoingContext(ctx, "x-movement-signature", Sign(c.Secret, operation, action, user, account, amount))
	_, err := c.Ledger.ApplyMovement(ctx, &ledgerv1.MovementRequest{
		OperationId: operation, Action: action, UserId: user, FromAccountId: &account, Amount: amount,
	})
	if err != nil {
		return fmt.Errorf("movement %s: %w", action, err)
	}
	return nil
}
