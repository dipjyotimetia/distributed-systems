package integration

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"

	"go.temporal.io/sdk/workflow"
)

type ledgerEntry struct {
	ID, UserID                 int64
	FromAccountID, ToAccountID int64
	Type, Status               string
	Amount                     float64
}

type ledgerMovement struct {
	Path, Type string
	Amount     int
}

var ledgerMovements = []ledgerMovement{
	{"/api/transactions/deposit", "DEPOSIT", 30},
	{"/api/transactions/withdraw", "WITHDRAWAL", 7},
	{"/api/transactions", "DEPOSIT", 11},
	{"/api/transactions/create", "WITHDRAWAL", 5},
}

func LedgerJourney(ctx workflow.Context, input Input) ([]BoundaryEvidence, error) {
	var a *Activities
	writes := workflow.WithActivityOptions(ctx, mutationOptions())
	reads := workflow.WithActivityOptions(ctx, evidenceOptions())
	var userID, accountID, otherAccountID int64
	other := Input{User: input.User + "b"}
	if err := workflow.ExecuteActivity(writes, a.Register, input).Get(ctx, &userID); err != nil {
		return nil, err
	}
	if err := workflow.ExecuteActivity(writes, a.Register, other).Get(ctx, nil); err != nil {
		return nil, err
	}
	if err := workflow.ExecuteActivity(writes, a.CreateAccount, input, "CHECKING", 100).Get(ctx, &accountID); err != nil {
		return nil, err
	}
	if err := workflow.ExecuteActivity(writes, a.CreateAccount, other, "CHECKING", 17).Get(ctx, &otherAccountID); err != nil {
		return nil, err
	}
	var entries []ledgerEntry
	for _, movement := range ledgerMovements {
		var entry ledgerEntry
		if err := workflow.ExecuteActivity(writes, a.MoveFunds, input, accountID, movement).Get(ctx, &entry); err != nil {
			return nil, err
		}
		entries = append(entries, entry)
	}
	if err := workflow.ExecuteActivity(writes, a.RejectWithdrawal, input, accountID).Get(ctx, nil); err != nil {
		return nil, err
	}
	if err := workflow.ExecuteActivity(writes, a.AssertSettledConflict, input, entries[0]).Get(ctx, nil); err != nil {
		return nil, err
	}
	var accounts, ledger, payments, enrichment BoundaryEvidence
	if err := workflow.ExecuteActivity(reads, a.VerifyAccounts, input, userID, accountID, otherAccountID).Get(ctx, &accounts); err != nil {
		return nil, err
	}
	if err := workflow.ExecuteActivity(reads, a.VerifyLedger, input, userID, accountID, entries).Get(ctx, &ledger); err != nil {
		return nil, err
	}
	if err := workflow.ExecuteActivity(reads, a.VerifyPayments, entries).Get(ctx, &payments); err != nil {
		return nil, err
	}
	if err := workflow.ExecuteActivity(reads, a.VerifyFinancialEnrichment).Get(ctx, &enrichment); err != nil {
		return nil, err
	}
	var pendingID int64
	if err := workflow.ExecuteActivity(writes, a.SeedPendingTransaction, userID, accountID).Get(ctx, &pendingID); err != nil {
		return nil, err
	}
	var cancellation BoundaryEvidence
	if err := workflow.ExecuteActivity(writes, a.AssertConditionalCancellation, input, pendingID).Get(ctx, &cancellation); err != nil {
		return nil, err
	}
	var update BoundaryEvidence
	if err := workflow.ExecuteActivity(writes, a.VerifyAccountTypeUpdate, input, userID, accountID).Get(ctx, &update); err != nil {
		return nil, err
	}
	var lifecycleID int64
	if err := workflow.ExecuteActivity(writes, a.CreateAccount, input, "SAVINGS", 0).Get(ctx, &lifecycleID); err != nil {
		return nil, err
	}
	var lifecycle BoundaryEvidence
	if err := workflow.ExecuteActivity(writes, a.VerifyAccountLifecycle, input, lifecycleID).Get(ctx, &lifecycle); err != nil {
		return nil, err
	}
	return []BoundaryEvidence{accounts, ledger, payments, enrichment, cancellation, update, lifecycle}, nil
}

func (a *Activities) MoveFunds(ctx context.Context, input Input, accountID int64, movement ledgerMovement) (ledgerEntry, error) {
	token, err := a.login(ctx, input)
	if err != nil {
		return ledgerEntry{}, err
	}
	payload := map[string]any{"accountId": accountID, "amount": movement.Amount, "type": movement.Type, "description": "Synthetic ledger movement"}
	if movement.Path == "/api/transactions" || movement.Path == "/api/transactions/create" {
		payload["currency"] = "USD"
	}
	var entry ledgerEntry
	err = call(ctx, a.Frontend, movement.Path, token, payload, 201, &entry)
	if err == nil && (entry.ID <= 0 || entry.Type != movement.Type || entry.Status != "COMPLETED" || entry.Amount != float64(movement.Amount)) {
		err = fmt.Errorf("incorrect ledger entry from %s", movement.Path)
	}
	return entry, err
}

func (a *Activities) RejectWithdrawal(ctx context.Context, input Input, accountID int64) error {
	token, err := a.login(ctx, input)
	if err != nil {
		return err
	}
	var failure struct{ Code string }
	if err := call(ctx, a.Frontend, "/api/transactions/withdraw", token, map[string]any{
		"accountId": accountID, "amount": 150, "description": "Synthetic insufficient funds",
	}, 400, &failure); err != nil {
		return err
	}
	if failure.Code != "INSUFFICIENT_FUNDS" {
		return fmt.Errorf("withdrawal did not return INSUFFICIENT_FUNDS")
	}
	return nil
}

type accountRecord struct {
	ID, UserID                    int64
	AccountType, Currency, Status string
	Balance                       float64
}

type page[T any] struct {
	Content       []T
	TotalElements int
	Number        int
	Size          int
}

func (a *Activities) VerifyAccounts(ctx context.Context, input Input, userID, accountID, otherAccountID int64) (BoundaryEvidence, error) {
	token, err := a.login(ctx, input)
	if err != nil {
		return BoundaryEvidence{}, err
	}
	expected := accountRecord{ID: accountID, UserID: userID, AccountType: "CHECKING", Currency: "USD", Status: "ACTIVE", Balance: 129}
	for _, path := range []string{"/api/accounts", "/api/accounts?page=0&size=2", "/api/accounts/search?q=checking&page=0&size=2"} {
		var records []accountRecord
		if path == "/api/accounts" {
			err = call(ctx, a.Frontend, path, token, nil, 200, &records)
		} else {
			var result page[accountRecord]
			err = call(ctx, a.Frontend, path, token, nil, 200, &result)
			records = result.Content
			if err == nil && (result.TotalElements != 1 || result.Number != 0 || result.Size != 2) {
				err = fmt.Errorf("incorrect account page metadata")
			}
		}
		if err != nil {
			return BoundaryEvidence{}, err
		}
		if len(records) != 1 || records[0] != expected {
			return BoundaryEvidence{}, fmt.Errorf("incorrect account scope or balance on %s", path)
		}
	}
	accountPath := fmt.Sprintf("/api/accounts/%d", accountID)
	var detail accountRecord
	if err := call(ctx, a.Frontend, accountPath, token, nil, 200, &detail); err != nil {
		return BoundaryEvidence{}, err
	}
	if detail != expected {
		return BoundaryEvidence{}, fmt.Errorf("account detail did not match owned account")
	}
	forbidden, err := request(ctx, http.MethodGet, a.Frontend, fmt.Sprintf("/api/accounts/%d", otherAccountID), token, nil)
	if err != nil {
		return BoundaryEvidence{}, err
	}
	if forbidden.Status != 404 || len(forbidden.Body) != 0 {
		return BoundaryEvidence{}, fmt.Errorf("foreign account detail must return an empty 404")
	}
	var balance struct {
		Balance  float64
		Currency string
	}
	if err := call(ctx, a.Frontend, accountPath+"/balance", token, nil, 200, &balance); err != nil {
		return BoundaryEvidence{}, err
	}
	if balance.Balance != 129 || balance.Currency != "USD" {
		return BoundaryEvidence{}, fmt.Errorf("account balance did not match the ledger")
	}
	var summary struct {
		TotalAccounts, ActiveAccounts int
		TotalBalance                  float64
		AccountsByType                map[string]int
	}
	if err := call(ctx, a.Frontend, "/api/accounts/summary", token, nil, 200, &summary); err != nil {
		return BoundaryEvidence{}, err
	}
	if summary.TotalAccounts != 1 || summary.ActiveAccounts != 1 || summary.TotalBalance != 129 || summary.AccountsByType["CHECKING"] != 1 {
		return BoundaryEvidence{}, fmt.Errorf("incorrect account summary")
	}
	var statement struct {
		Status    string
		Statement struct {
			AccountID, UserID int64
			Balance           float64
			ExportedAt        string
		}
	}
	if err := call(ctx, a.Frontend, accountPath+"/export-statement", token, struct{}{}, 200, &statement); err != nil {
		return BoundaryEvidence{}, err
	}
	if statement.Status != "generated" || statement.Statement.AccountID != accountID || statement.Statement.UserID != userID || statement.Statement.Balance != 129 || statement.Statement.ExportedAt == "" {
		return BoundaryEvidence{}, fmt.Errorf("incorrect inline account statement")
	}
	return BoundaryEvidence{"Frontend accounts", "one owned account, USD 129, scoped list/search/summary and inline statement", "scoped account reads and statement matched USD 129; foreign account returned an empty HTTP 404"}, nil
}

func (a *Activities) VerifyLedger(ctx context.Context, input Input, userID, accountID int64, entries []ledgerEntry) (BoundaryEvidence, error) {
	token, err := a.login(ctx, input)
	if err != nil {
		return BoundaryEvidence{}, err
	}
	expected := make(map[int64]ledgerEntry)
	for _, entry := range entries {
		if entry.UserID != userID || expected[entry.ID].ID != 0 ||
			(entry.Type == "DEPOSIT" && (entry.ToAccountID != accountID || entry.FromAccountID != 0)) ||
			(entry.Type == "WITHDRAWAL" && (entry.FromAccountID != accountID || entry.ToAccountID != 0)) {
			return BoundaryEvidence{}, fmt.Errorf("wrong ledger ownership, direction, or entry ID")
		}
		expected[entry.ID] = entry
	}
	accountPath := fmt.Sprintf("/api/accounts/%d/transactions", accountID)
	for _, path := range []string{"/api/transactions", "/api/transactions/recent?limit=4", accountPath} {
		var rows []ledgerEntry
		if err := call(ctx, a.Frontend, path, token, nil, 200, &rows); err != nil {
			return BoundaryEvidence{}, err
		}
		if len(rows) != len(entries) {
			return BoundaryEvidence{}, fmt.Errorf("wrong ledger count on %s", path)
		}
		for index, row := range rows {
			if row != entries[len(entries)-1-index] {
				return BoundaryEvidence{}, fmt.Errorf("wrong ledger entry or sort order on %s", path)
			}
		}
	}
	for _, path := range []string{"/api/transactions?page=0&size=2&type=DEPOSIT", "/api/transactions/search?page=0&size=2&type=DEPOSIT"} {
		var result page[ledgerEntry]
		if err := call(ctx, a.Frontend, path, token, nil, 200, &result); err != nil {
			return BoundaryEvidence{}, err
		}
		if result.Number != 0 || result.Size != 2 || result.TotalElements != 2 ||
			len(result.Content) != 2 || result.Content[0] != entries[2] || result.Content[1] != entries[0] {
			return BoundaryEvidence{}, fmt.Errorf("wrong filtered ledger page on %s", path)
		}
	}
	for _, path := range []string{"/api/transactions/summary", accountPath + "/summary"} {
		var summary struct {
			TotalTransactions, TotalDeposits, TotalWithdrawals, CompletedTransactions int
		}
		if err := call(ctx, a.Frontend, path, token, nil, 200, &summary); err != nil {
			return BoundaryEvidence{}, err
		}
		if summary.TotalTransactions != 4 || summary.TotalDeposits != 2 || summary.TotalWithdrawals != 2 || summary.CompletedTransactions != 4 {
			return BoundaryEvidence{}, fmt.Errorf("incorrect ledger summary on %s", path)
		}
	}
	detailPath := fmt.Sprintf("/api/transactions/%d", entries[0].ID)
	var detail ledgerEntry
	if err := call(ctx, a.Frontend, detailPath, token, nil, 200, &detail); err != nil {
		return BoundaryEvidence{}, err
	}
	if detail != entries[0] {
		return BoundaryEvidence{}, fmt.Errorf("ledger detail did not match original response")
	}
	otherToken, err := a.login(ctx, Input{User: input.User + "b"})
	if err != nil {
		return BoundaryEvidence{}, err
	}
	for _, path := range []string{detailPath, accountPath, accountPath + "/summary"} {
		if err := call(ctx, a.Frontend, path, otherToken, nil, 404, nil); err != nil {
			return BoundaryEvidence{}, err
		}
	}
	return BoundaryEvidence{"Frontend ledger", "four completed, scoped ledger entries and no entry for insufficient funds",
		"deposits and withdrawals matched exact entries across list, detail, recent, search, account history and summaries"}, nil
}

func (a *Activities) AssertSettledConflict(ctx context.Context, input Input, entry ledgerEntry) error {
	token, err := a.login(ctx, input)
	if err != nil {
		return err
	}
	path := fmt.Sprintf("/api/transactions/%d/cancel", entry.ID)
	response, err := request(ctx, http.MethodPatch, a.Frontend, path, token, nil)
	if err != nil {
		return err
	}
	if response.Status != 409 {
		return fmt.Errorf("settled transaction cancellation returned %d, expected 409", response.Status)
	}
	return nil
}

func (a *Activities) VerifyPayments(ctx context.Context, entries []ledgerEntry) (BoundaryEvidence, error) {
	var payments map[string]map[string]int
	if err := call(ctx, a.Deliveries, "/payment-evidence", "", nil, 200, &payments); err != nil {
		return BoundaryEvidence{}, err
	}
	var checks struct{ Compliance map[string]int }
	if err := call(ctx, a.Deliveries, "/checks", "", nil, 200, &checks); err != nil {
		return BoundaryEvidence{}, err
	}
	for _, entry := range entries {
		if payments["stripe"][fmt.Sprintf("%.0f", entry.Amount)] != 1 ||
			payments["paypal"][fmt.Sprintf("%.2f", entry.Amount)] != 1 ||
			checks.Compliance[fmt.Sprintf("Transaction %d", entry.ID)] != 1 {
			return BoundaryEvidence{}, fmt.Errorf("expected one request per payment provider for transaction %d", entry.ID)
		}
	}
	if len(payments["stripe"]) != len(entries) || len(payments["paypal"]) != len(entries) || len(checks.Compliance) != len(entries) {
		return BoundaryEvidence{}, fmt.Errorf("found unexpected payment provider requests")
	}
	return BoundaryEvidence{"Payment fan-out", "one Stripe, PayPal and ComplyAdvantage request per completed movement",
		"all four movements produced one fixture request per provider"}, nil
}

func (a *Activities) VerifyAccountTypeUpdate(ctx context.Context, input Input, userID, accountID int64) (BoundaryEvidence, error) {
	path := fmt.Sprintf("/api/accounts/%d", accountID)
	payload := map[string]string{"accountType": "SAVINGS"}
	other, err := a.login(ctx, Input{User: input.User + "b"})
	if err != nil {
		return BoundaryEvidence{}, err
	}
	forbidden, err := request(ctx, http.MethodPut, a.Frontend, path, other, payload)
	if err != nil || forbidden.Status != 404 {
		return BoundaryEvidence{}, fmt.Errorf("foreign account update must return HTTP 404")
	}
	token, err := a.login(ctx, input)
	if err != nil {
		return BoundaryEvidence{}, err
	}
	updated, err := request(ctx, http.MethodPut, a.Frontend, path, token, payload)
	if err != nil {
		return BoundaryEvidence{}, err
	}
	expected := accountRecord{ID: accountID, UserID: userID, AccountType: "SAVINGS", Currency: "USD", Status: "ACTIVE", Balance: 129}
	var result accountRecord
	if updated.Status != 200 || json.Unmarshal(updated.Body, &result) != nil || result != expected {
		return BoundaryEvidence{}, fmt.Errorf("account update did not preserve ownership or balance")
	}
	if err := call(ctx, a.Frontend, path, token, nil, 200, &result); err != nil {
		return BoundaryEvidence{}, err
	}
	if result != expected {
		return BoundaryEvidence{}, fmt.Errorf("account type update did not persist")
	}
	return BoundaryEvidence{"Account metadata", "owned account type changes with balance preserved",
		"owned account changed to SAVINGS and retained USD 129; foreign request returned HTTP 404"}, nil
}
