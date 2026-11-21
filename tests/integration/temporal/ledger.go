package integration

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"

	"go.temporal.io/sdk/workflow"
	"microsvc/integration/temporal/openapi/accounts"
	"microsvc/integration/temporal/openapi/transactions"
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
	{"/api/v1/transactions/deposit", "DEPOSIT", 30},
	{"/api/v1/transactions/withdraw", "WITHDRAWAL", 7},
	{"/api/v1/transactions", "DEPOSIT", 11},
	{"/api/v1/transactions/create", "WITHDRAWAL", 5},
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
	client, err := a.transactionsAPI()
	if err != nil {
		return ledgerEntry{}, err
	}
	editor := transactions.RequestEditorFn(bearer(token))
	description := text("Synthetic ledger movement")
	var recorded *transactions.TransactionResponse
	var status int
	switch movement.Path {
	case "/api/v1/transactions/deposit":
		result, err := client.DepositWithResponse(ctx, transactions.DepositRequest{
			AccountId: accountID, Amount: float32(movement.Amount), Description: description,
		}, editor)
		if err != nil {
			return ledgerEntry{}, fmt.Errorf("POST %s request failed", movement.Path)
		}
		status, recorded = result.StatusCode(), result.JSON201
	case "/api/v1/transactions/withdraw":
		result, err := client.WithdrawWithResponse(ctx, transactions.WithdrawRequest{
			AccountId: accountID, Amount: float32(movement.Amount), Description: description,
		}, editor)
		if err != nil {
			return ledgerEntry{}, fmt.Errorf("POST %s request failed", movement.Path)
		}
		status, recorded = result.StatusCode(), result.JSON201
	case "/api/v1/transactions/create":
		result, err := client.CreateTransactionWithResponse(ctx, transactions.CreateTransactionJSONRequestBody{
			AccountId: accountID, Amount: float32(movement.Amount), Currency: "USD",
			Description: description, Type: transactions.CreateTransactionJSONBodyType(movement.Type),
		}, editor)
		if err != nil {
			return ledgerEntry{}, fmt.Errorf("POST %s request failed", movement.Path)
		}
		status, recorded = result.StatusCode(), result.JSON201
	case "/api/v1/transactions":
		// The frontend accepts the create-transaction model on the collection path and forwards it to /create.
		body := transactions.CreateTransactionJSONBody{
			AccountId: accountID, Amount: float32(movement.Amount), Currency: "USD",
			Description: description, Type: transactions.CreateTransactionJSONBodyType(movement.Type),
		}
		response, err := request(ctx, http.MethodPost, a.Frontend, movement.Path, token, body)
		if err != nil {
			return ledgerEntry{}, err
		}
		status = response.Status
		if status == http.StatusCreated {
			var decoded transactions.TransactionResponse
			if err := json.Unmarshal(response.Body, &decoded); err != nil {
				return ledgerEntry{}, fmt.Errorf("decode %s response", movement.Path)
			}
			recorded = &decoded
		}
	default:
		return ledgerEntry{}, fmt.Errorf("unknown ledger movement %s", movement.Path)
	}
	if status != http.StatusCreated || recorded == nil {
		return ledgerEntry{}, unexpectedStatus(http.MethodPost, movement.Path, status, http.StatusCreated)
	}
	entry := ledgerFrom(*recorded)
	if entry.ID <= 0 || entry.Type != movement.Type || entry.Status != "COMPLETED" || entry.Amount != float64(movement.Amount) {
		return ledgerEntry{}, fmt.Errorf("incorrect ledger entry from %s", movement.Path)
	}
	return entry, nil
}

func (a *Activities) RejectWithdrawal(ctx context.Context, input Input, accountID int64) error {
	token, err := a.login(ctx, input)
	if err != nil {
		return err
	}
	client, err := a.transactionsAPI()
	if err != nil {
		return err
	}
	result, err := client.WithdrawWithResponse(ctx, transactions.WithdrawRequest{
		AccountId: accountID, Amount: 150, Description: text("Synthetic insufficient funds"),
	}, transactions.RequestEditorFn(bearer(token)))
	if err != nil {
		return fmt.Errorf("POST /api/v1/transactions/withdraw request failed")
	}
	if result.StatusCode() != http.StatusBadRequest {
		return unexpectedStatus(http.MethodPost, "/api/v1/transactions/withdraw", result.StatusCode(), http.StatusBadRequest)
	}
	if result.JSON400 == nil || result.JSON400.Code != transactions.INSUFFICIENTFUNDS {
		return fmt.Errorf("withdrawal did not return INSUFFICIENT_FUNDS")
	}
	return nil
}

type accountRecord struct {
	ID, UserID                    int64
	AccountType, Currency, Status string
	Balance                       float64
}

func (a *Activities) VerifyAccounts(ctx context.Context, input Input, userID, accountID, otherAccountID int64) (BoundaryEvidence, error) {
	token, err := a.login(ctx, input)
	if err != nil {
		return BoundaryEvidence{}, err
	}
	client, err := a.accountsAPI()
	if err != nil {
		return BoundaryEvidence{}, err
	}
	editor := accounts.RequestEditorFn(bearer(token))
	expected := accountRecord{ID: accountID, UserID: userID, AccountType: "CHECKING", Currency: "USD", Status: "ACTIVE", Balance: 129}
	pageNumber, pageSize := 0, 2
	query := "checking"
	lists := []struct {
		path  string
		paged bool
		load  func() (*accounts.AccountListOrPage, int, error)
	}{
		{"/api/v1/accounts", false, func() (*accounts.AccountListOrPage, int, error) {
			result, err := client.ListAccountsWithResponse(ctx, &accounts.ListAccountsParams{}, editor)
			if err != nil || result == nil {
				return nil, 0, err
			}
			return result.JSON200, result.StatusCode(), nil
		}},
		{"/api/v1/accounts?page=0&size=2", true, func() (*accounts.AccountListOrPage, int, error) {
			result, err := client.ListAccountsWithResponse(ctx, &accounts.ListAccountsParams{Page: &pageNumber, Size: &pageSize}, editor)
			if err != nil || result == nil {
				return nil, 0, err
			}
			return result.JSON200, result.StatusCode(), nil
		}},
		{"/api/v1/accounts/search?q=checking&page=0&size=2", true, func() (*accounts.AccountListOrPage, int, error) {
			result, err := client.SearchAccountsWithResponse(ctx, &accounts.SearchAccountsParams{Q: &query, Page: &pageNumber, Size: &pageSize}, editor)
			if err != nil || result == nil {
				return nil, 0, err
			}
			return result.JSON200, result.StatusCode(), nil
		}},
	}
	for _, list := range lists {
		body, status, err := list.load()
		if err != nil {
			return BoundaryEvidence{}, fmt.Errorf("GET %s request failed", list.path)
		}
		if status != http.StatusOK || body == nil {
			return BoundaryEvidence{}, unexpectedStatus(http.MethodGet, list.path, status, http.StatusOK)
		}
		var records []accounts.AccountResponse
		if list.paged {
			page, err := body.AsAccountPage()
			if err != nil || page.TotalElements != 1 || page.Number != 0 || page.Size != 2 {
				return BoundaryEvidence{}, fmt.Errorf("incorrect account page metadata")
			}
			records = page.Content
		} else {
			records, err = body.AsAccountListOrPage0()
			if err != nil {
				return BoundaryEvidence{}, fmt.Errorf("decode %s response", list.path)
			}
		}
		if len(records) != 1 || accountFrom(records[0]) != expected {
			return BoundaryEvidence{}, fmt.Errorf("incorrect account scope or balance on %s", list.path)
		}
	}
	detail, err := client.GetAccountWithResponse(ctx, accountID, editor)
	if err != nil {
		return BoundaryEvidence{}, fmt.Errorf("GET /api/v1/accounts/%d request failed", accountID)
	}
	if detail.StatusCode() != http.StatusOK || detail.JSON200 == nil || accountFrom(*detail.JSON200) != expected {
		return BoundaryEvidence{}, fmt.Errorf("account detail did not match owned account")
	}
	forbidden, err := client.GetAccountWithResponse(ctx, otherAccountID, editor)
	if err != nil {
		return BoundaryEvidence{}, err
	}
	if forbidden.StatusCode() != http.StatusNotFound || len(forbidden.Body) != 0 {
		return BoundaryEvidence{}, fmt.Errorf("foreign account detail must return an empty 404")
	}
	balance, err := client.GetAccountBalanceWithResponse(ctx, accountID, editor)
	if err != nil {
		return BoundaryEvidence{}, fmt.Errorf("GET /api/v1/accounts/%d/balance request failed", accountID)
	}
	if balance.StatusCode() != http.StatusOK || balance.JSON200 == nil || amountValue(balance.JSON200.Balance) != 129 || textValue(balance.JSON200.Currency) != "USD" {
		return BoundaryEvidence{}, fmt.Errorf("account balance did not match the ledger")
	}
	summary, err := client.SummarizeAccountsWithResponse(ctx, editor)
	if err != nil {
		return BoundaryEvidence{}, fmt.Errorf("GET /api/v1/accounts/summary request failed")
	}
	if summary.StatusCode() != http.StatusOK || summary.JSON200 == nil || summary.JSON200.TotalAccounts == nil || *summary.JSON200.TotalAccounts != 1 ||
		summary.JSON200.ActiveAccounts == nil || *summary.JSON200.ActiveAccounts != 1 || amountValue(summary.JSON200.TotalBalance) != 129 ||
		summary.JSON200.AccountsByType == nil || (*summary.JSON200.AccountsByType)["CHECKING"] != 1 {
		return BoundaryEvidence{}, fmt.Errorf("incorrect account summary")
	}
	exported, err := client.ExportStatementWithResponse(ctx, accountID, editor)
	if err != nil {
		return BoundaryEvidence{}, fmt.Errorf("POST /api/v1/accounts/%d/export-statement request failed", accountID)
	}
	if exported.StatusCode() != http.StatusOK {
		return BoundaryEvidence{}, unexpectedStatus(http.MethodPost, fmt.Sprintf("/api/v1/accounts/%d/export-statement", accountID), exported.StatusCode(), http.StatusOK)
	}
	status, _, statement, err := statementFrom(exported.JSON200)
	if err != nil || status != "generated" || statement.AccountID != accountID || statement.UserID != userID || statement.Balance != 129 || statement.ExportedAt == "" {
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
	accountPath := fmt.Sprintf("/api/v1/accounts/%d/transactions", accountID)
	lists := []struct {
		path string
		rows transactions.TransactionList
		err  error
	}{
		{path: "/api/v1/transactions"},
		{path: "/api/v1/transactions/recent?limit=4"},
		{path: accountPath},
	}
	lists[0].rows, lists[0].err = a.transactionList(ctx, token, nil)
	limit := 4
	recent, err := a.transactionsAPI()
	if err != nil {
		return BoundaryEvidence{}, err
	}
	recentResult, err := recent.RecentTransactionsWithResponse(ctx, &transactions.RecentTransactionsParams{Limit: &limit}, transactions.RequestEditorFn(bearer(token)))
	if err != nil {
		return BoundaryEvidence{}, fmt.Errorf("GET /api/v1/transactions/recent request failed")
	}
	if recentResult.StatusCode() != http.StatusOK || recentResult.JSON200 == nil {
		return BoundaryEvidence{}, unexpectedStatus(http.MethodGet, "/api/v1/transactions/recent", recentResult.StatusCode(), http.StatusOK)
	}
	lists[1].rows = *recentResult.JSON200
	var accountRows transactions.TransactionList
	if err := call(ctx, a.Frontend, accountPath, token, nil, http.StatusOK, &accountRows); err != nil {
		return BoundaryEvidence{}, err
	}
	lists[2].rows = accountRows
	for _, list := range lists {
		if list.err != nil {
			return BoundaryEvidence{}, list.err
		}
		if len(list.rows) != len(entries) {
			return BoundaryEvidence{}, fmt.Errorf("wrong ledger count on %s", list.path)
		}
		for index, row := range list.rows {
			if ledgerFrom(row) != entries[len(entries)-1-index] {
				return BoundaryEvidence{}, fmt.Errorf("wrong ledger entry or sort order on %s", list.path)
			}
		}
	}
	pageNumber, pageSize := 0, 2
	deposit := transactions.Type("DEPOSIT")
	pages := []struct {
		path string
		body *transactions.TransactionListOrPage
		code int
		err  error
	}{
		{path: "/api/v1/transactions?page=0&size=2&type=DEPOSIT"},
		{path: "/api/v1/transactions/search?page=0&size=2&type=DEPOSIT"},
	}
	filtered, err := recent.ListTransactionsWithResponse(ctx, &transactions.ListTransactionsParams{Page: &pageNumber, Size: &pageSize, Type: &deposit}, transactions.RequestEditorFn(bearer(token)))
	if err != nil {
		return BoundaryEvidence{}, fmt.Errorf("GET /api/v1/transactions request failed")
	}
	pages[0].body, pages[0].code = filtered.JSON200, filtered.StatusCode()
	searched, err := recent.SearchTransactionsWithResponse(ctx, &transactions.SearchTransactionsParams{Page: &pageNumber, Size: &pageSize, Type: &deposit}, transactions.RequestEditorFn(bearer(token)))
	if err != nil {
		return BoundaryEvidence{}, fmt.Errorf("GET /api/v1/transactions/search request failed")
	}
	pages[1].body, pages[1].code = searched.JSON200, searched.StatusCode()
	for _, page := range pages {
		if page.code != http.StatusOK || page.body == nil {
			return BoundaryEvidence{}, unexpectedStatus(http.MethodGet, page.path, page.code, http.StatusOK)
		}
		result, err := page.body.AsTransactionPage()
		if err != nil || result.Number != 0 || result.Size != 2 || result.TotalElements != 2 ||
			len(result.Content) != 2 || ledgerFrom(result.Content[0]) != entries[2] || ledgerFrom(result.Content[1]) != entries[0] {
			return BoundaryEvidence{}, fmt.Errorf("wrong filtered ledger page on %s", page.path)
		}
	}
	summaries := []struct {
		path string
		body *transactions.TransactionSummary
		code int
	}{{path: "/api/v1/transactions/summary"}, {path: accountPath + "/summary"}}
	ownedSummary, err := recent.SummarizeTransactionsWithResponse(ctx, &transactions.SummarizeTransactionsParams{}, transactions.RequestEditorFn(bearer(token)))
	if err != nil {
		return BoundaryEvidence{}, fmt.Errorf("GET /api/v1/transactions/summary request failed")
	}
	summaries[0].body, summaries[0].code = ownedSummary.JSON200, ownedSummary.StatusCode()
	var accountSummary transactions.TransactionSummary
	if err := call(ctx, a.Frontend, accountPath+"/summary", token, nil, http.StatusOK, &accountSummary); err != nil {
		return BoundaryEvidence{}, err
	}
	summaries[1].body, summaries[1].code = &accountSummary, http.StatusOK
	for _, summary := range summaries {
		if summary.code != http.StatusOK || summary.body == nil || summary.body.TotalTransactions == nil || *summary.body.TotalTransactions != 4 ||
			summary.body.TotalDeposits == nil || *summary.body.TotalDeposits != 2 || summary.body.TotalWithdrawals == nil || *summary.body.TotalWithdrawals != 2 ||
			summary.body.CompletedTransactions == nil || *summary.body.CompletedTransactions != 4 {
			return BoundaryEvidence{}, fmt.Errorf("incorrect ledger summary on %s", summary.path)
		}
	}
	detail, err := recent.GetTransactionWithResponse(ctx, entries[0].ID, transactions.RequestEditorFn(bearer(token)))
	if err != nil {
		return BoundaryEvidence{}, fmt.Errorf("GET /api/v1/transactions/%d request failed", entries[0].ID)
	}
	if detail.StatusCode() != http.StatusOK || detail.JSON200 == nil || ledgerFrom(*detail.JSON200) != entries[0] {
		return BoundaryEvidence{}, fmt.Errorf("ledger detail did not match original response")
	}
	otherToken, err := a.login(ctx, Input{User: input.User + "b"})
	if err != nil {
		return BoundaryEvidence{}, err
	}
	foreignDetail, err := recent.GetTransactionWithResponse(ctx, entries[0].ID, transactions.RequestEditorFn(bearer(otherToken)))
	if err != nil {
		return BoundaryEvidence{}, err
	}
	if foreignDetail.StatusCode() != http.StatusNotFound {
		return BoundaryEvidence{}, unexpectedStatus(http.MethodGet, fmt.Sprintf("/api/v1/transactions/%d", entries[0].ID), foreignDetail.StatusCode(), http.StatusNotFound)
	}
	for _, path := range []string{accountPath, accountPath + "/summary"} {
		if err := call(ctx, a.Frontend, path, otherToken, nil, http.StatusNotFound, nil); err != nil {
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
	client, err := a.transactionsAPI()
	if err != nil {
		return err
	}
	response, err := client.CancelTransactionWithResponse(ctx, entry.ID, transactions.RequestEditorFn(bearer(token)))
	if err != nil {
		return fmt.Errorf("PATCH /api/v1/transactions/%d/cancel request failed", entry.ID)
	}
	if response.StatusCode() != http.StatusConflict {
		return fmt.Errorf("settled transaction cancellation returned %d, expected 409", response.StatusCode())
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
	other, err := a.login(ctx, Input{User: input.User + "b"})
	if err != nil {
		return BoundaryEvidence{}, err
	}
	client, err := a.accountsAPI()
	if err != nil {
		return BoundaryEvidence{}, err
	}
	kind := accounts.AccountUpdateRequestAccountTypeSAVINGS
	body := accounts.AccountUpdateRequest{AccountType: &kind}
	forbidden, err := client.UpdateAccountWithResponse(ctx, accountID, body, accounts.RequestEditorFn(bearer(other)))
	if err != nil || forbidden.StatusCode() != http.StatusNotFound {
		return BoundaryEvidence{}, fmt.Errorf("foreign account update must return HTTP 404")
	}
	token, err := a.login(ctx, input)
	if err != nil {
		return BoundaryEvidence{}, err
	}
	editor := accounts.RequestEditorFn(bearer(token))
	updated, err := client.UpdateAccountWithResponse(ctx, accountID, body, editor)
	if err != nil {
		return BoundaryEvidence{}, fmt.Errorf("PUT /api/v1/accounts/%d request failed", accountID)
	}
	expected := accountRecord{ID: accountID, UserID: userID, AccountType: "SAVINGS", Currency: "USD", Status: "ACTIVE", Balance: 129}
	if updated.StatusCode() != http.StatusOK || updated.JSON200 == nil || accountFrom(*updated.JSON200) != expected {
		return BoundaryEvidence{}, fmt.Errorf("account update did not preserve ownership or balance")
	}
	persisted, err := client.GetAccountWithResponse(ctx, accountID, editor)
	if err != nil || persisted.StatusCode() != http.StatusOK || persisted.JSON200 == nil || accountFrom(*persisted.JSON200) != expected {
		return BoundaryEvidence{}, fmt.Errorf("account type update did not persist")
	}
	return BoundaryEvidence{"Account metadata", "owned account type changes with balance preserved",
		"owned account changed to SAVINGS and retained USD 129; foreign request returned HTTP 404"}, nil
}
