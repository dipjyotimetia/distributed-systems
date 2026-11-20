package ledger

import (
	"bytes"
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"math/big"
	"net/http"
	"strings"
)

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
	Base   string
	Secret string
	HTTP   *http.Client
}

func (c Client) Owns(ctx context.Context, authorization string, account int64) error {
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, fmt.Sprintf("%s/api/accounts/%d", c.Base, account), nil)
	if err != nil {
		return err
	}
	request.Header.Set("Authorization", authorization)
	response, err := c.client().Do(request)
	if err != nil {
		return err
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
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
	body, _ := json.Marshal(map[string]any{"userId": user, "fromAccountId": account, "toAccountId": nil, "amount": json.Number(amount)})
	request, err := http.NewRequestWithContext(ctx, http.MethodPost, fmt.Sprintf("%s/internal/account-movements/%s/%s", c.Base, operation, action), bytes.NewReader(body))
	if err != nil {
		return err
	}
	request.Header.Set("Content-Type", "application/json")
	request.Header.Set("X-Movement-Signature", Sign(c.Secret, operation, action, user, account, amount))
	response, err := c.client().Do(request)
	if err != nil {
		return err
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusNoContent {
		return fmt.Errorf("movement %s returned %d", action, response.StatusCode)
	}
	return nil
}

func (c Client) client() *http.Client {
	if c.HTTP != nil {
		return c.HTTP
	}
	return http.DefaultClient
}
