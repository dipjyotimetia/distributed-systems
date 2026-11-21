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
	var result struct{ Token string }
	err := call(ctx, a.Frontend, "/api/v1/users/login", "", map[string]string{"usernameOrEmail": input.User, "password": a.Password}, 200, &result)
	if err == nil && result.Token == "" {
		err = fmt.Errorf("login returned an empty token")
	}
	return result.Token, err
}

func (a *Activities) Register(ctx context.Context, input Input) (int64, error) {
	var user struct{ ID int64 }
	err := call(ctx, a.Frontend, "/api/v1/users/register", "", map[string]string{
		"username": input.User, "email": input.email(), "password": a.Password,
	}, 201, &user)
	if err == nil && user.ID <= 0 {
		err = fmt.Errorf("registration response missing user ID")
	}
	return user.ID, err
}

func (a *Activities) CreateAccount(ctx context.Context, input Input, kind string, balance int) (int64, error) {
	token, err := a.login(ctx, input)
	if err != nil {
		return 0, err
	}
	var account struct{ ID int64 }
	err = call(ctx, a.Frontend, "/api/v1/accounts", token, map[string]any{
		"accountType": kind, "initialBalance": balance,
	}, 201, &account)
	if err == nil && account.ID <= 0 {
		err = fmt.Errorf("account response missing ID")
	}
	return account.ID, err
}

func (a *Activities) SubmitTransfer(ctx context.Context, input Input, t Transfer) (int64, error) {
	token, err := a.login(ctx, input)
	if err != nil {
		return 0, err
	}
	var result struct{ ID int64 }
	err = call(ctx, a.Frontend, "/api/v1/transactions/transfer", token, transferBody(t, 25), 201, &result)
	if err == nil && result.ID <= 0 {
		err = fmt.Errorf("transfer response missing ID")
	}
	return result.ID, err
}

func transferBody(t Transfer, amount int) map[string]any {
	return map[string]any{"fromAccountId": t.From, "toAccountId": t.To, "amount": amount, "description": "Temporal integration"}
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
	var rejection struct{ Code string }
	if err = call(ctx, a.Frontend, "/api/v1/transactions/transfer", token, transferBody(t, 90), 400, &rejection); err != nil {
		return err
	}
	if rejection.Code != "INSUFFICIENT_FUNDS" {
		return fmt.Errorf("transfer rejection did not return INSUFFICIENT_FUNDS")
	}
	return nil
}

func (a *Activities) verify(ctx context.Context, token string, t Transfer) error {
	for _, account := range []struct {
		id      int64
		balance int
	}{{t.From, 75}, {t.To, 45}} {
		var result struct{ Balance *json.Number }
		path := fmt.Sprintf("/api/v1/accounts/%d/balance", account.id)
		if err := call(ctx, a.Frontend, path, token, nil, 200, &result); err != nil {
			return err
		}
		if result.Balance == nil {
			return fmt.Errorf("account %d response missing balance", account.id)
		}
		value, err := result.Balance.Float64()
		if err != nil || value != float64(account.balance) {
			return fmt.Errorf("account %d expected balance %d", account.id, account.balance)
		}
	}
	var history []struct {
		ID, FromAccountID, ToAccountID int64
		Amount                         float64
		Type, Status                   string
	}
	if err := call(ctx, a.Frontend, "/api/v1/transactions", token, nil, 200, &history); err != nil {
		return err
	}
	if len(history) != 1 {
		return fmt.Errorf("expected one transaction, got %d", len(history))
	}
	entry := history[0]
	if entry.ID != t.ID || entry.FromAccountID != t.From || entry.ToAccountID != t.To ||
		entry.Amount != 25 || entry.Type != "TRANSFER" || entry.Status != "COMPLETED" {
		return fmt.Errorf("transaction history did not match committed transfer %d", t.ID)
	}
	return nil
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
		rejection, err := request(ctx, http.MethodPost, a.Frontend, "/api/v1/transactions/transfer", token, transferBody(transfer, amount))
		if err != nil {
			return err
		}
		if rejection.Status != 400 || len(rejection.Body) != 0 {
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
