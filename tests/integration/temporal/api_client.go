package integration

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"strings"

	banking "microsvc/integration/temporal/gen"
)

func (a *Activities) api(token string) (*banking.ClientWithResponses, error) {
	return banking.NewClientWithResponses(strings.TrimRight(a.Frontend, "/")+"/api",
		banking.WithRequestEditorFn(func(_ context.Context, req *http.Request) error {
			if token != "" {
				req.Header.Set("Authorization", "Bearer "+token)
			}
			return nil
		}))
}

func expectStatus(operation string, got, want int) error {
	if got != want {
		return fmt.Errorf("%s returned %d, expected %d", operation, got, want)
	}
	return nil
}

func decodeBody[T any](body []byte) (T, error) {
	var value T
	err := json.Unmarshal(bytes.TrimSpace(body), &value)
	return value, err
}

func accountRows(body []byte) ([]banking.AccountResponse, error) {
	if bytes.HasPrefix(bytes.TrimSpace(body), []byte("[")) {
		return decodeBody[banking.AccountList](body)
	}
	page, err := decodeBody[banking.AccountPage](body)
	return page.Content, err
}

func transactionRows(body []byte) ([]banking.TransactionResponse, error) {
	if bytes.HasPrefix(bytes.TrimSpace(body), []byte("[")) {
		return decodeBody[banking.TransactionList](body)
	}
	page, err := decodeBody[banking.TransactionPage](body)
	return page.Content, err
}

func ledgerFrom(tx banking.TransactionResponse) ledgerEntry {
	return ledgerEntry{
		ID: tx.Id, UserID: tx.UserId,
		FromAccountID: ident(tx.FromAccountId), ToAccountID: ident(tx.ToAccountId),
		Type: string(tx.Type), Status: string(tx.Status), Amount: tx.Amount,
	}
}

func accountFrom(account banking.AccountResponse) accountRecord {
	return accountRecord{
		ID: account.Id, UserID: account.UserId, AccountType: account.AccountType,
		Currency: account.Currency, Status: string(account.Status), Balance: float64(account.Balance),
	}
}

func ident(value *int64) int64 {
	if value == nil {
		return 0
	}
	return *value
}

func text(value *string) string {
	if value == nil {
		return ""
	}
	return *value
}

func strPtr(value string) *string { return &value }

func transferRequest(t Transfer, amount int) banking.TransferRequest {
	return banking.TransferRequest{
		FromAccountId: t.From, ToAccountId: t.To, Amount: float64(amount),
		Description: strPtr("Temporal integration"),
	}
}
