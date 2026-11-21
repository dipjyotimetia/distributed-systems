package integration

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"slices"
	"time"

	"microsvc/integration/temporal/openapi/accounts"
	"microsvc/integration/temporal/openapi/transactions"
	"microsvc/integration/temporal/openapi/users"
)

type Activities struct {
	Frontend, Password                                                    string
	Deliveries                                                            string
	BrokerContainer, MongoContainer, ConsumerContainer, PostgresContainer string
	FailedProvider                                                        string
}

var apiClient = &http.Client{Timeout: 7 * time.Second, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}

type HTTPResponse struct {
	Status int
	Body   []byte
}

func request(ctx context.Context, method, base, path, token string, body any) (HTTPResponse, error) {
	var payload []byte
	if body != nil {
		var err error
		payload, err = json.Marshal(body)
		if err != nil {
			return HTTPResponse{}, fmt.Errorf("encode %s", path)
		}
	}
	req, err := http.NewRequestWithContext(ctx, method, base+path, bytes.NewReader(payload))
	if err != nil {
		return HTTPResponse{}, fmt.Errorf("build %s request", path)
	}
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	res, err := apiClient.Do(req)
	if err != nil {
		return HTTPResponse{}, fmt.Errorf("%s %s request failed", method, path)
	}
	defer res.Body.Close()
	const maxBody = 1 << 20
	data, err := io.ReadAll(io.LimitReader(res.Body, maxBody+1))
	if err != nil || len(data) > maxBody {
		return HTTPResponse{}, fmt.Errorf("read %s response", path)
	}
	return HTTPResponse{Status: res.StatusCode, Body: data}, nil
}

func call(ctx context.Context, base, path, token string, body any, status int, dst any) error {
	method := http.MethodGet
	if body != nil {
		method = http.MethodPost
	}
	res, err := request(ctx, method, base, path, token, body)
	if err != nil {
		return err
	}
	if res.Status != status {
		return fmt.Errorf("%s %s returned %d, expected %d", method, path, res.Status, status)
	}
	if dst != nil {
		if err := json.Unmarshal(res.Body, dst); err != nil {
			return fmt.Errorf("decode %s response", path)
		}
	}
	return nil
}

func (a *Activities) login(ctx context.Context, input Input) (string, error) {
	client, err := a.usersAPI()
	if err != nil {
		return "", err
	}
	result, err := client.LoginWithResponse(ctx, users.LoginRequest{UsernameOrEmail: input.User, Password: a.Password})
	if err != nil {
		return "", fmt.Errorf("POST /api/v1/users/login request failed")
	}
	if result.StatusCode() != http.StatusOK {
		return "", unexpectedStatus(http.MethodPost, "/api/v1/users/login", result.StatusCode(), http.StatusOK)
	}
	if result.JSON200 == nil || result.JSON200.Token == nil || *result.JSON200.Token == "" {
		return "", fmt.Errorf("login returned an empty token")
	}
	return *result.JSON200.Token, nil
}

func (a *Activities) Register(ctx context.Context, input Input) (int64, error) {
	client, err := a.usersAPI()
	if err != nil {
		return 0, err
	}
	result, err := client.RegisterWithResponse(ctx, users.RegisterRequest{
		Username: input.User, Email: input.email(), Password: a.Password,
	})
	if err != nil {
		return 0, fmt.Errorf("POST /api/v1/users/register request failed")
	}
	if result.StatusCode() != http.StatusCreated {
		return 0, unexpectedStatus(http.MethodPost, "/api/v1/users/register", result.StatusCode(), http.StatusCreated)
	}
	if result.JSON201 == nil || result.JSON201.Id == nil || *result.JSON201.Id <= 0 {
		return 0, fmt.Errorf("registration response missing user ID")
	}
	return *result.JSON201.Id, nil
}

func (a *Activities) CreateAccount(ctx context.Context, input Input, kind string, balance int) (int64, error) {
	token, err := a.login(ctx, input)
	if err != nil {
		return 0, err
	}
	client, err := a.accountsAPI()
	if err != nil {
		return 0, err
	}
	initial := float32(balance)
	result, err := client.CreateAccountWithResponse(ctx, accounts.AccountCreateRequest{
		AccountType: accounts.AccountCreateRequestAccountType(kind), InitialBalance: &initial,
	}, accounts.RequestEditorFn(bearer(token)))
	if err != nil {
		return 0, fmt.Errorf("POST /api/v1/accounts request failed")
	}
	if result.StatusCode() != http.StatusCreated {
		return 0, unexpectedStatus(http.MethodPost, "/api/v1/accounts", result.StatusCode(), http.StatusCreated)
	}
	if result.JSON201 == nil || result.JSON201.Id == nil || *result.JSON201.Id <= 0 {
		return 0, fmt.Errorf("account response missing ID")
	}
	return *result.JSON201.Id, nil
}

func (a *Activities) SubmitTransfer(ctx context.Context, input Input, t Transfer) (int64, error) {
	token, err := a.login(ctx, input)
	if err != nil {
		return 0, err
	}
	result, err := a.transfer(ctx, token, transferRequest(t, 25))
	if err != nil {
		return 0, err
	}
	if result.StatusCode() != http.StatusCreated || result.JSON201 == nil || result.JSON201.Id == nil || *result.JSON201.Id <= 0 {
		if result.StatusCode() != http.StatusCreated {
			return 0, unexpectedStatus(http.MethodPost, "/api/v1/transactions/transfer", result.StatusCode(), http.StatusCreated)
		}
		return 0, fmt.Errorf("transfer response missing ID")
	}
	return *result.JSON201.Id, nil
}

func (a *Activities) transfer(ctx context.Context, token string, body transactions.TransferRequest) (*transactions.TransferHTTP, error) {
	client, err := a.transactionsAPI()
	if err != nil {
		return nil, err
	}
	result, err := client.TransferWithResponse(ctx, body, transactions.RequestEditorFn(bearer(token)))
	if err != nil {
		return nil, fmt.Errorf("POST /api/v1/transactions/transfer request failed")
	}
	return result, nil
}

func (a *Activities) Verify(ctx context.Context, input Input, t Transfer) error {
	token, err := a.login(ctx, input)
	if err != nil {
		return err
	}
	return a.verify(ctx, token, t)
}

func (a *Activities) Reject(ctx context.Context, input Input, t Transfer) error {
	token, err := a.login(ctx, input)
	if err != nil {
		return err
	}
	result, err := a.transfer(ctx, token, transferRequest(t, 90))
	if err != nil {
		return err
	}
	if result.StatusCode() != http.StatusBadRequest || !insufficientFunds(result.JSON400) {
		return fmt.Errorf("transfer rejection did not return INSUFFICIENT_FUNDS")
	}
	return nil
}

func (a *Activities) verify(ctx context.Context, token string, t Transfer) error {
	accountsClient, err := a.accountsAPI()
	if err != nil {
		return err
	}
	editor := accounts.RequestEditorFn(bearer(token))
	for _, account := range []struct {
		id      int64
		balance int
	}{{t.From, 75}, {t.To, 45}} {
		result, err := accountsClient.GetAccountBalanceWithResponse(ctx, account.id, editor)
		if err != nil {
			return fmt.Errorf("GET /api/v1/accounts/%d/balance request failed", account.id)
		}
		if result.StatusCode() != http.StatusOK {
			return unexpectedStatus(http.MethodGet, fmt.Sprintf("/api/v1/accounts/%d/balance", account.id), result.StatusCode(), http.StatusOK)
		}
		if result.JSON200 == nil || result.JSON200.Balance == nil || float64(*result.JSON200.Balance) != float64(account.balance) {
			return fmt.Errorf("account %d expected balance %d", account.id, account.balance)
		}
	}
	history, err := a.transactionList(ctx, token, nil)
	if err != nil {
		return err
	}
	if len(history) != 1 {
		return fmt.Errorf("expected one transaction, got %d", len(history))
	}
	entry := ledgerFrom(history[0])
	if entry.ID != t.ID || entry.FromAccountID != t.From || entry.ToAccountID != t.To ||
		entry.Amount != 25 || entry.Type != "TRANSFER" || entry.Status != "COMPLETED" {
		return fmt.Errorf("transaction history did not match committed transfer %d", t.ID)
	}
	return nil
}

func (a *Activities) transactionList(ctx context.Context, token string, params *transactions.ListTransactionsParams) (transactions.TransactionList, error) {
	client, err := a.transactionsAPI()
	if err != nil {
		return nil, err
	}
	result, err := client.ListTransactionsWithResponse(ctx, params, transactions.RequestEditorFn(bearer(token)))
	if err != nil {
		return nil, fmt.Errorf("GET /api/v1/transactions request failed")
	}
	if result.StatusCode() != http.StatusOK || result.JSON200 == nil {
		return nil, unexpectedStatus(http.MethodGet, "/api/v1/transactions", result.StatusCode(), http.StatusOK)
	}
	if params != nil && (params.Page != nil || params.Size != nil) {
		page, err := result.JSON200.AsTransactionPage()
		return page.Content, err
	}
	return result.JSON200.AsTransactionList()
}

func (a *Activities) VerifyChecks(ctx context.Context, t Transfer) error {
	var evidence struct {
		Fraud, Compliance map[string]int
	}
	if err := call(ctx, a.Deliveries, "/checks", "", nil, 200, &evidence); err != nil {
		return err
	}
	for _, amount := range []int{25, 90} {
		key := fmt.Sprintf("%d/%d/TRANSFER/%d.00", t.UserID, t.From, amount)
		if count := evidence.Fraud[key]; count != 1 {
			return fmt.Errorf("expected one fraud check for transfer amount %d, got %d", amount, count)
		}
	}
	key := fmt.Sprintf("Transfer %d to %d", t.From, t.To)
	if count := evidence.Compliance[key]; count != 2 {
		return fmt.Errorf("expected two transfer compliance checks, got %d", count)
	}
	return nil
}

func (a *Activities) AssertFraudRejections(ctx context.Context, input Input, transfer Transfer) error {
	token, err := a.login(ctx, input)
	if err != nil {
		return err
	}
	for _, amount := range []int{1200, 700} {
		if amount == 700 {
			control, err := request(ctx, http.MethodPut, a.Deliveries, "/fraud-failure", "", nil)
			if err != nil || control.Status != 204 {
				return fmt.Errorf("configure fraud provider failure")
			}
		}
		rejection, err := a.transfer(ctx, token, transferRequest(transfer, amount))
		if err != nil {
			return err
		}
		if rejection.StatusCode() != http.StatusBadRequest || len(rejection.Body) != 0 || rejection.JSON400 != nil {
			return fmt.Errorf("expected empty fraud rejection for transfer amount %d", amount)
		}
	}
	return nil
}

func (a *Activities) VerifyFraudRejections(ctx context.Context, transfer Transfer) (BoundaryEvidence, error) {
	var checks struct {
		Fraud, Compliance map[string]int
		ProviderStatuses  map[string][]int
	}
	if err := call(ctx, a.Deliveries, "/checks", "", nil, 200, &checks); err != nil {
		return BoundaryEvidence{}, err
	}
	for _, amount := range []int{25, 90, 1200, 700} {
		key := fmt.Sprintf("%d/%d/TRANSFER/%d.00", transfer.UserID, transfer.From, amount)
		if checks.Fraud[key] != 1 {
			return BoundaryEvidence{}, fmt.Errorf("wrong Sift evidence for transfer amount %d", amount)
		}
	}
	for _, provider := range []string{"radar", "sift", "maxmind"} {
		if !slices.Equal(checks.ProviderStatuses[provider], []int{200, 200, 200, 503}) {
			return BoundaryEvidence{}, fmt.Errorf("wrong fraud request status sequence for %s", provider)
		}
	}
	key := fmt.Sprintf("Transfer %d to %d", transfer.From, transfer.To)
	if checks.Compliance[key] != 2 {
		return BoundaryEvidence{}, fmt.Errorf("fraud-rejected transfer reached compliance")
	}
	return BoundaryEvidence{"Fraud and compliance", "approval, rule denial and all-provider failure without new ledger entries",
		"real fraud service called Radar, Sift and MaxMind; balances, ledger and compliance counts held after both denials"}, nil
}
