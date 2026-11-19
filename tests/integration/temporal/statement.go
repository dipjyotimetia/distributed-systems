package integration

import (
	"context"
	"fmt"
	"strings"

	"go.temporal.io/sdk/workflow"
)

type accountStatement struct {
	AccountID, UserID       int64
	AccountType, ExportedAt string
	Balance                 float64
}

func StatementJourney(ctx workflow.Context, input Input) ([]BoundaryEvidence, error) {
	var a *Activities
	writes := workflow.WithActivityOptions(ctx, mutationOptions())
	reads := workflow.WithActivityOptions(ctx, evidenceOptions())
	var userID, accountID int64
	if err := workflow.ExecuteActivity(writes, a.Register, input).Get(ctx, &userID); err != nil {
		return nil, err
	}
	if err := workflow.ExecuteActivity(writes, a.CreateAccount, input, "SAVINGS", 40).Get(ctx, &accountID); err != nil {
		return nil, err
	}
	var key string
	if err := workflow.ExecuteActivity(writes, a.ExportStatement, input, accountID).Get(ctx, &key); err != nil {
		return nil, err
	}
	var statement BoundaryEvidence
	if err := workflow.ExecuteActivity(reads, a.VerifyStoredStatement, key, userID, accountID).Get(ctx, &statement); err != nil {
		return nil, err
	}
	return []BoundaryEvidence{statement}, nil
}

func (a *Activities) ExportStatement(ctx context.Context, input Input, accountID int64) (string, error) {
	token, err := a.login(ctx, input)
	if err != nil {
		return "", err
	}
	var response struct{ Status, Key string }
	path := fmt.Sprintf("/api/accounts/%d/export-statement", accountID)
	if err := call(ctx, a.Frontend, path, token, struct{}{}, 200, &response); err != nil {
		return "", err
	}
	prefix := fmt.Sprintf("statements/account-%d/", accountID)
	if response.Status != "exported" || !strings.HasPrefix(response.Key, prefix) ||
		!strings.HasSuffix(response.Key, ".json") || strings.Contains(strings.TrimPrefix(response.Key, prefix), "/") {
		return "", fmt.Errorf("statement response did not identify the exported account object")
	}
	return response.Key, nil
}

func (a *Activities) VerifyStoredStatement(ctx context.Context, key string, userID, accountID int64) (BoundaryEvidence, error) {
	var stored accountStatement
	if err := call(ctx, a.Deliveries, "/statements-test/"+key, "", nil, 200, &stored); err != nil {
		return BoundaryEvidence{}, err
	}
	if stored.AccountID != accountID || stored.UserID != userID || stored.Balance != 40 || stored.AccountType != "SAVINGS" || stored.ExportedAt == "" {
		return BoundaryEvidence{}, fmt.Errorf("stored statement did not match the source account")
	}
	return BoundaryEvidence{"S3 statement", "one object containing the owned savings account and USD 40",
		"read back the statement by returned key and verified ownership, type and balance"}, nil
}

func (a *Activities) VerifyFinancialEnrichment(ctx context.Context) (BoundaryEvidence, error) {
	var attempts map[string][]int
	if err := call(ctx, a.Deliveries, "/financial-evidence", "", nil, 200, &attempts); err != nil {
		return BoundaryEvidence{}, err
	}
	expected := map[string]int{
		"/plaid/sandbox/public_token/create": 200,
		"/plaid/item/public_token/exchange":  200,
		"/plaid/accounts/balance/get":        200,
		"/exchange":                          200,
		"/ratings":                           503,
	}
	for path, status := range expected {
		if len(attempts[path]) == 0 {
			return BoundaryEvidence{}, fmt.Errorf("missing financial enrichment evidence for %s", path)
		}
		for _, observed := range attempts[path] {
			if observed != status {
				return BoundaryEvidence{}, fmt.Errorf("wrong financial enrichment fixture status")
			}
		}
	}
	return BoundaryEvidence{"Financial enrichment", "Plaid and exchange rate requests succeed while ratings fail",
		"fixture received all enrichment requests and the frontend balance and ledger remained correct"}, nil
}
