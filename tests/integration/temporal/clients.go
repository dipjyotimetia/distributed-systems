package integration

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"time"

	"microsvc/integration/temporal/openapi/accounts"
	"microsvc/integration/temporal/openapi/ai"
	"microsvc/integration/temporal/openapi/transactions"
	"microsvc/integration/temporal/openapi/users"
)

func bearer(token string) func(context.Context, *http.Request) error {
	return func(_ context.Context, req *http.Request) error {
		if token != "" {
			req.Header.Set("Authorization", "Bearer "+token)
		}
		return nil
	}
}

func (a *Activities) usersAPI() (*users.ClientWithResponses, error) {
	return users.NewClientWithResponses(a.Frontend, users.WithHTTPClient(apiClient))
}

func (a *Activities) accountsAPI() (*accounts.ClientWithResponses, error) {
	return accounts.NewClientWithResponses(a.Frontend, accounts.WithHTTPClient(apiClient))
}

func (a *Activities) transactionsAPI() (*transactions.ClientWithResponses, error) {
	return transactions.NewClientWithResponses(a.Frontend, transactions.WithHTTPClient(apiClient))
}

func (a *Activities) aiAPI() (*ai.ClientWithResponses, error) {
	return ai.NewClientWithResponses(a.Frontend, ai.WithHTTPClient(aiHTTP))
}

var aiHTTP = &http.Client{
	Timeout:       7 * time.Second,
	CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse },
	Transport:     aiFacade{base: http.DefaultTransport},
}

// aiFacade maps the AI contract operations onto the frontend route that proxies both of them.
type aiFacade struct{ base http.RoundTripper }

func (f aiFacade) RoundTrip(req *http.Request) (*http.Response, error) {
	next := req.Clone(req.Context())
	if next.URL.Path == "/api/v1/providers" || (req.Method == http.MethodPost && next.URL.Path == "/api/v1/chat") {
		next.URL.Path = "/api/v1/ai/chat"
	}
	base := f.base
	if base == nil {
		base = http.DefaultTransport
	}
	return base.RoundTrip(next)
}

func unexpectedStatus(method, path string, got, want int) error {
	return fmt.Errorf("%s %s returned %d, expected %d", method, path, got, want)
}

func text(value string) *string { return &value }

func int64Value(value *int64) int64 {
	if value == nil {
		return 0
	}
	return *value
}

func textValue(value *string) string {
	if value == nil {
		return ""
	}
	return *value
}

func amountValue(value *float32) float64 {
	if value == nil {
		return 0
	}
	return float64(*value)
}

func ledgerFrom(tx transactions.TransactionResponse) ledgerEntry {
	return ledgerEntry{
		ID:            int64Value(tx.Id),
		UserID:        int64Value(tx.UserId),
		FromAccountID: int64Value(tx.FromAccountId),
		ToAccountID:   int64Value(tx.ToAccountId),
		Type:          textValue(tx.Type),
		Status:        textValue(tx.Status),
		Amount:        amountValue(tx.Amount),
	}
}

func accountFrom(account accounts.AccountResponse) accountRecord {
	status := ""
	if account.Status != nil {
		status = string(*account.Status)
	}
	return accountRecord{
		ID:          int64Value(account.Id),
		UserID:      int64Value(account.UserId),
		AccountType: textValue(account.AccountType),
		Currency:    textValue(account.Currency),
		Status:      status,
		Balance:     amountValue(account.Balance),
	}
}

func statementFrom(export *accounts.StatementExport) (string, string, accountStatement, error) {
	if export == nil {
		return "", "", accountStatement{}, fmt.Errorf("statement response missing body")
	}
	var stored accountStatement
	if export.Statement != nil {
		raw, err := json.Marshal(export.Statement)
		if err != nil {
			return "", "", accountStatement{}, fmt.Errorf("encode statement response")
		}
		if err = json.Unmarshal(raw, &stored); err != nil {
			return "", "", accountStatement{}, fmt.Errorf("decode statement response")
		}
	}
	return textValue(export.Status), textValue(export.Key), stored, nil
}

func transferRequest(t Transfer, amount int) transactions.TransferRequest {
	return transactions.TransferRequest{
		FromAccountId: t.From,
		ToAccountId:   t.To,
		Amount:        float32(amount),
		Description:   text("Temporal integration"),
	}
}
