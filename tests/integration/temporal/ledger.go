package integration

import (
	"context"
	"fmt"
	"net/http"

	"go.temporal.io/sdk/workflow"
	banking "microsvc/integration/temporal/gen"
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
	client, err := a.api(token)
	if err != nil {
		return ledgerEntry{}, err
	}
	description := strPtr("Synthetic ledger movement")
	var status int
	var body []byte
	var created *banking.TransactionResponse
	switch movement.Path {
	case "/api/transactions/deposit":
		res, err := client.DepositWithResponse(ctx, nil, banking.DepositRequest{AccountId: accountID, Amount: float64(movement.Amount), Description: description})
		if err != nil {
			return ledgerEntry{}, err
		}
		status, body, created = res.StatusCode(), res.Body, res.JSON201
	case "/api/transactions/withdraw":
		res, err := client.WithdrawWithResponse(ctx, nil, banking.WithdrawRequest{AccountId: accountID, Amount: float64(movement.Amount), Description: description})
		if err != nil {
			return ledgerEntry{}, err
		}
		status, body, created = res.StatusCode(), res.Body, res.JSON201
	case "/api/transactions":
		res, err := client.SubmitTransactionWithResponse(ctx, nil, banking.CreateTransactionRequest{
			AccountId: accountID, Amount: float64(movement.Amount), Currency: "USD", Type: banking.TransactionType(movement.Type), Description: description,
		})
		if err != nil {
			return ledgerEntry{}, err
		}
		status, body, created = res.StatusCode(), res.Body, res.JSON201
	case "/api/transactions/create":
		res, err := client.CreateTransactionWithResponse(ctx, nil, banking.CreateTransactionRequest{
			AccountId: accountID, Amount: float64(movement.Amount), Currency: "USD", Type: banking.TransactionType(movement.Type), Description: description,
		})
		if err != nil {
			return ledgerEntry{}, err
		}
		status, body, created = res.StatusCode(), res.Body, res.JSON201
	default:
		return ledgerEntry{}, fmt.Errorf("unknown ledger path %s", movement.Path)
	}
	if status != http.StatusCreated || created == nil {
		return ledgerEntry{}, fmt.Errorf("%s returned %d, expected 201: %s", movement.Path, status, body)
	}
	entry := ledgerFrom(*created)
	if entry.ID <= 0 || entry.Type != movement.Type || entry.Status != "COMPLETED" || entry.Amount != float64(movement.Amount) {
		return entry, fmt.Errorf("incorrect ledger entry from %s", movement.Path)
	}
	return entry, nil
}

func (a *Activities) RejectWithdrawal(ctx context.Context, input Input, accountID int64) error {
	token, err := a.login(ctx, input)
	if err != nil {
		return err
	}
	client, err := a.api(token)
	if err != nil {
		return err
	}
	res, err := client.WithdrawWithResponse(ctx, nil, banking.WithdrawRequest{
		AccountId: accountID, Amount: 150, Description: strPtr("Synthetic insufficient funds"),
	})
	if err != nil {
		return err
	}
	if err = expectStatus("POST /transactions/withdraw", res.StatusCode(), http.StatusBadRequest); err != nil {
		return err
	}
	funds, err := decodeBody[banking.InsufficientFunds](res.Body)
	if err != nil || funds.Code != banking.INSUFFICIENTFUNDS {
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
	client, err := a.api(token)
	if err != nil {
		return BoundaryEvidence{}, err
	}
	expected := accountRecord{ID: accountID, UserID: userID, AccountType: "CHECKING", Currency: "USD", Status: "ACTIVE", Balance: 129}
	page, size := 0, 2
	query := "checking"
	lists := []struct {
		name string
		load func() ([]banking.AccountResponse, int, error)
	}{
		{"GET /accounts", func() ([]banking.AccountResponse, int, error) {
			res, err := client.ListAccountsWithResponse(ctx, nil)
			if err != nil {
				return nil, 0, err
			}
			rows, err := accountRows(res.Body)
			return rows, res.StatusCode(), err
		}},
		{"GET /accounts?page", func() ([]banking.AccountResponse, int, error) {
			res, err := client.ListAccountsWithResponse(ctx, &banking.ListAccountsParams{Page: &page, Size: &size})
			if err != nil {
				return nil, 0, err
			}
			parsed, err := decodeBody[banking.AccountPage](res.Body)
			if err == nil && (parsed.TotalElements != 1 || parsed.Number != 0 || parsed.Size != 2) {
				err = fmt.Errorf("incorrect account page metadata")
			}
			return parsed.Content, res.StatusCode(), err
		}},
		{"GET /accounts/search", func() ([]banking.AccountResponse, int, error) {
			res, err := client.SearchAccountsWithResponse(ctx, &banking.SearchAccountsParams{Q: &query, Page: &page, Size: &size})
			if err != nil {
				return nil, 0, err
			}
			if res.JSON200 == nil {
				return nil, res.StatusCode(), fmt.Errorf("missing account page")
			}
			if res.JSON200.TotalElements != 1 || res.JSON200.Number != 0 || res.JSON200.Size != 2 {
				return res.JSON200.Content, res.StatusCode(), fmt.Errorf("incorrect account page metadata")
			}
			return res.JSON200.Content, res.StatusCode(), nil
		}},
	}
	for _, list := range lists {
		rows, status, err := list.load()
		if err != nil {
			return BoundaryEvidence{}, err
		}
		if err = expectStatus(list.name, status, http.StatusOK); err != nil {
			return BoundaryEvidence{}, err
		}
		if len(rows) != 1 || accountFrom(rows[0]) != expected {
			return BoundaryEvidence{}, fmt.Errorf("incorrect account scope or balance on %s", list.name)
		}
	}
	detail, err := client.GetAccountWithResponse(ctx, accountID)
	if err != nil {
		return BoundaryEvidence{}, err
	}
	if err = expectStatus("GET /accounts/{id}", detail.StatusCode(), http.StatusOK); err != nil || detail.JSON200 == nil || accountFrom(*detail.JSON200) != expected {
		return BoundaryEvidence{}, fmt.Errorf("account detail did not match owned account")
	}
	forbidden, err := client.GetAccountWithResponse(ctx, otherAccountID)
	if err != nil {
		return BoundaryEvidence{}, err
	}
	if forbidden.StatusCode() != http.StatusNotFound || len(forbidden.Body) != 0 {
		return BoundaryEvidence{}, fmt.Errorf("foreign account detail must return an empty 404")
	}
	balance, err := client.GetAccountBalanceWithResponse(ctx, accountID)
	if err != nil {
		return BoundaryEvidence{}, err
	}
	if err = expectStatus("GET /accounts/{id}/balance", balance.StatusCode(), http.StatusOK); err != nil || balance.JSON200 == nil || float64(balance.JSON200.Balance) != 129 || balance.JSON200.Currency != "USD" {
		return BoundaryEvidence{}, fmt.Errorf("account balance did not match the ledger")
	}
	summary, err := client.SummarizeAccountsWithResponse(ctx)
	if err != nil {
		return BoundaryEvidence{}, err
	}
	if err = expectStatus("GET /accounts/summary", summary.StatusCode(), http.StatusOK); err != nil || summary.JSON200 == nil || summary.JSON200.TotalAccounts != 1 || summary.JSON200.ActiveAccounts != 1 || float64(summary.JSON200.TotalBalance) != 129 || summary.JSON200.AccountsByType["CHECKING"] != 1 {
		return BoundaryEvidence{}, fmt.Errorf("incorrect account summary")
	}
	statement, err := client.ExportAccountStatementWithResponse(ctx, accountID)
	if err != nil {
		return BoundaryEvidence{}, err
	}
	if err = expectStatus("POST /accounts/{id}/export-statement", statement.StatusCode(), http.StatusOK); err != nil || statement.JSON200 == nil || statement.JSON200.Status != banking.Generated || statement.JSON200.Statement == nil || statement.JSON200.Statement.AccountId != accountID || statement.JSON200.Statement.UserId != userID || float64(statement.JSON200.Statement.Balance) != 129 || statement.JSON200.Statement.ExportedAt.Time().IsZero() {
		return BoundaryEvidence{}, fmt.Errorf("incorrect inline account statement")
	}
	return BoundaryEvidence{"Frontend accounts", "one owned account, USD 129, scoped list/search/summary and inline statement", "scoped account reads and statement matched USD 129; foreign account returned an empty HTTP 404"}, nil
}

func (a *Activities) VerifyLedger(ctx context.Context, input Input, userID, accountID int64, entries []ledgerEntry) (BoundaryEvidence, error) {
	token, err := a.login(ctx, input)
	if err != nil {
		return BoundaryEvidence{}, err
	}
	client, err := a.api(token)
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
	limit := 4
	lists := []struct {
		name string
		load func() ([]banking.TransactionResponse, int, error)
	}{
		{"GET /transactions", func() ([]banking.TransactionResponse, int, error) {
			res, err := client.ListTransactionsWithResponse(ctx, nil)
			if err != nil {
				return nil, 0, err
			}
			rows, err := transactionRows(res.Body)
			return rows, res.StatusCode(), err
		}},
		{"GET /transactions/recent", func() ([]banking.TransactionResponse, int, error) {
			res, err := client.RecentTransactionsWithResponse(ctx, &banking.RecentTransactionsParams{Limit: &limit})
			if err != nil {
				return nil, 0, err
			}
			if res.JSON200 == nil {
				return nil, res.StatusCode(), fmt.Errorf("missing recent transactions")
			}
			return *res.JSON200, res.StatusCode(), nil
		}},
		{"GET /accounts/{id}/transactions", func() ([]banking.TransactionResponse, int, error) {
			res, err := client.ListAccountTransactionsWithResponse(ctx, accountID, nil)
			if err != nil {
				return nil, 0, err
			}
			rows, err := transactionRows(res.Body)
			return rows, res.StatusCode(), err
		}},
	}
	for _, list := range lists {
		rows, status, err := list.load()
		if err != nil {
			return BoundaryEvidence{}, err
		}
		if err = expectStatus(list.name, status, http.StatusOK); err != nil {
			return BoundaryEvidence{}, err
		}
		if len(rows) != len(entries) {
			return BoundaryEvidence{}, fmt.Errorf("wrong ledger count on %s", list.name)
		}
		for index, row := range rows {
			if ledgerFrom(row) != entries[len(entries)-1-index] {
				return BoundaryEvidence{}, fmt.Errorf("wrong ledger entry or sort order on %s", list.name)
			}
		}
	}
	page, size := 0, 2
	deposit := banking.DEPOSIT
	pages := []struct {
		name string
		load func() (banking.TransactionPage, int, error)
	}{
		{"GET /transactions?type=DEPOSIT", func() (banking.TransactionPage, int, error) {
			res, err := client.ListTransactionsWithResponse(ctx, &banking.ListTransactionsParams{Page: &page, Size: &size, Type: &deposit})
			if err != nil {
				return banking.TransactionPage{}, 0, err
			}
			parsed, err := decodeBody[banking.TransactionPage](res.Body)
			return parsed, res.StatusCode(), err
		}},
		{"GET /transactions/search?type=DEPOSIT", func() (banking.TransactionPage, int, error) {
			res, err := client.SearchTransactionsWithResponse(ctx, &banking.SearchTransactionsParams{Page: &page, Size: &size, Type: &deposit})
			if err != nil {
				return banking.TransactionPage{}, 0, err
			}
			if res.JSON200 == nil {
				return banking.TransactionPage{}, res.StatusCode(), fmt.Errorf("missing transaction page")
			}
			return *res.JSON200, res.StatusCode(), nil
		}},
	}
	for _, list := range pages {
		result, status, err := list.load()
		if err != nil {
			return BoundaryEvidence{}, err
		}
		if err = expectStatus(list.name, status, http.StatusOK); err != nil {
			return BoundaryEvidence{}, err
		}
		if result.Number != 0 || result.Size != 2 || result.TotalElements != 2 ||
			len(result.Content) != 2 || ledgerFrom(result.Content[0]) != entries[2] || ledgerFrom(result.Content[1]) != entries[0] {
			return BoundaryEvidence{}, fmt.Errorf("wrong filtered ledger page on %s", list.name)
		}
	}
	summaries := []struct {
		name string
		load func() (*banking.TransactionSummary, int, error)
	}{
		{"GET /transactions/summary", func() (*banking.TransactionSummary, int, error) {
			res, err := client.SummarizeTransactionsWithResponse(ctx, nil)
			if err != nil {
				return nil, 0, err
			}
			return res.JSON200, res.StatusCode(), nil
		}},
		{"GET /accounts/{id}/transactions/summary", func() (*banking.TransactionSummary, int, error) {
			res, err := client.SummarizeAccountTransactionsWithResponse(ctx, accountID)
			if err != nil {
				return nil, 0, err
			}
			return res.JSON200, res.StatusCode(), nil
		}},
	}
	for _, list := range summaries {
		summary, status, err := list.load()
		if err != nil {
			return BoundaryEvidence{}, err
		}
		if err = expectStatus(list.name, status, http.StatusOK); err != nil || summary == nil || summary.TotalTransactions != 4 || summary.TotalDeposits != 2 || summary.TotalWithdrawals != 2 || summary.CompletedTransactions != 4 {
			return BoundaryEvidence{}, fmt.Errorf("incorrect ledger summary on %s", list.name)
		}
	}
	detail, err := client.GetTransactionWithResponse(ctx, entries[0].ID)
	if err != nil {
		return BoundaryEvidence{}, err
	}
	if err = expectStatus("GET /transactions/{id}", detail.StatusCode(), http.StatusOK); err != nil || detail.JSON200 == nil || ledgerFrom(*detail.JSON200) != entries[0] {
		return BoundaryEvidence{}, fmt.Errorf("ledger detail did not match original response")
	}
	otherToken, err := a.login(ctx, Input{User: input.User + "b"})
	if err != nil {
		return BoundaryEvidence{}, err
	}
	other, err := a.api(otherToken)
	if err != nil {
		return BoundaryEvidence{}, err
	}
	foreignDetail, err := other.GetTransactionWithResponse(ctx, entries[0].ID)
	if err != nil || foreignDetail.StatusCode() != http.StatusNotFound {
		return BoundaryEvidence{}, fmt.Errorf("foreign transaction detail must return HTTP 404")
	}
	foreignHistory, err := other.ListAccountTransactionsWithResponse(ctx, accountID, nil)
	if err != nil || foreignHistory.StatusCode() != http.StatusNotFound {
		return BoundaryEvidence{}, fmt.Errorf("foreign account history must return HTTP 404")
	}
	foreignSummary, err := other.SummarizeAccountTransactionsWithResponse(ctx, accountID)
	if err != nil || foreignSummary.StatusCode() != http.StatusNotFound {
		return BoundaryEvidence{}, fmt.Errorf("foreign account summary must return HTTP 404")
	}
	return BoundaryEvidence{"Frontend ledger", "four completed, scoped ledger entries and no entry for insufficient funds",
		"deposits and withdrawals matched exact entries across list, detail, recent, search, account history and summaries"}, nil
}

func (a *Activities) AssertSettledConflict(ctx context.Context, input Input, entry ledgerEntry) error {
	token, err := a.login(ctx, input)
	if err != nil {
		return err
	}
	client, err := a.api(token)
	if err != nil {
		return err
	}
	response, err := client.CancelTransactionWithResponse(ctx, entry.ID)
	if err != nil {
		return err
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
	savings := banking.SAVINGS
	body := banking.AccountUpdateRequest{AccountType: &savings}
	otherToken, err := a.login(ctx, Input{User: input.User + "b"})
	if err != nil {
		return BoundaryEvidence{}, err
	}
	other, err := a.api(otherToken)
	if err != nil {
		return BoundaryEvidence{}, err
	}
	forbidden, err := other.UpdateAccountWithResponse(ctx, accountID, body)
	if err != nil || forbidden.StatusCode() != http.StatusNotFound {
		return BoundaryEvidence{}, fmt.Errorf("foreign account update must return HTTP 404")
	}
	token, err := a.login(ctx, input)
	if err != nil {
		return BoundaryEvidence{}, err
	}
	client, err := a.api(token)
	if err != nil {
		return BoundaryEvidence{}, err
	}
	updated, err := client.UpdateAccountWithResponse(ctx, accountID, body)
	if err != nil {
		return BoundaryEvidence{}, err
	}
	expected := accountRecord{ID: accountID, UserID: userID, AccountType: "SAVINGS", Currency: "USD", Status: "ACTIVE", Balance: 129}
	if updated.StatusCode() != http.StatusOK || updated.JSON200 == nil || accountFrom(*updated.JSON200) != expected {
		return BoundaryEvidence{}, fmt.Errorf("account update did not preserve ownership or balance")
	}
	detail, err := client.GetAccountWithResponse(ctx, accountID)
	if err != nil || detail.StatusCode() != http.StatusOK || detail.JSON200 == nil || accountFrom(*detail.JSON200) != expected {
		return BoundaryEvidence{}, fmt.Errorf("account type update did not persist")
	}
	return BoundaryEvidence{"Account metadata", "owned account type changes with balance preserved",
		"owned account changed to SAVINGS and retained USD 129; foreign request returned HTTP 404"}, nil
}
