package integration

import (
	"context"
	"fmt"
	"net/http"
	"strings"

	"go.temporal.io/sdk/workflow"
	banking "microsvc/integration/temporal/gen"
)

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
	client, err := a.api(token)
	if err != nil {
		return "", err
	}
	response, err := client.ExportAccountStatementWithResponse(ctx, accountID)
	if err != nil {
		return "", err
	}
	if err = expectStatus("POST /accounts/{id}/export-statement", response.StatusCode(), http.StatusOK); err != nil {
		return "", err
	}
	prefix := fmt.Sprintf("statements/account-%d/", accountID)
	key := ""
	if response.JSON200 != nil && response.JSON200.Key != nil {
		key = *response.JSON200.Key
	}
	if response.JSON200 == nil || response.JSON200.Status != banking.Exported || !strings.HasPrefix(key, prefix) ||
		!strings.HasSuffix(key, ".json") || strings.Contains(strings.TrimPrefix(key, prefix), "/") {
		return "", fmt.Errorf("statement response did not identify the exported account object")
	}
	return key, nil
}

func (a *Activities) VerifyStoredStatement(ctx context.Context, key string, userID, accountID int64) (BoundaryEvidence, error) {
	var stored banking.StatementDocument
	if err := call(ctx, a.Deliveries, "/statements-test/"+key, "", nil, 200, &stored); err != nil {
		return BoundaryEvidence{}, err
	}
	if stored.AccountId != accountID || stored.UserId != userID || stored.Balance != 40 || stored.AccountType != "SAVINGS" || stored.ExportedAt.Time().IsZero() {
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
