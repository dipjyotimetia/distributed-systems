package integration

import (
	"context"
	"fmt"
	"net/http"

	"microsvc/integration/temporal/openapi/accounts"
	"microsvc/integration/temporal/openapi/transactions"
)

func (a *Activities) VerifyAccountLifecycle(ctx context.Context, input Input, id int64) (BoundaryEvidence, error) {
	token, err := a.login(ctx, input)
	if err != nil {
		return BoundaryEvidence{}, err
	}
	other, err := a.login(ctx, Input{User: input.User + "b"})
	if err != nil {
		return BoundaryEvidence{}, err
	}
	accountsClient, err := a.accountsAPI()
	if err != nil {
		return BoundaryEvidence{}, err
	}
	foreign, err := accountsClient.CloseAccountWithResponse(ctx, id, accounts.RequestEditorFn(bearer(other)))
	if err != nil || foreign.StatusCode() != http.StatusNotFound {
		return BoundaryEvidence{}, fmt.Errorf("foreign account closure must return HTTP 404")
	}
	editor := accounts.RequestEditorFn(bearer(token))
	change := func(status accounts.StatusUpdateRequestStatus, expected int) error {
		response, err := accountsClient.UpdateAccountStatusWithResponse(ctx, id, accounts.StatusUpdateRequest{Status: status}, editor)
		if err != nil {
			return err
		}
		if response.StatusCode() != expected {
			return fmt.Errorf("account status %s returned %d, expected %d", status, response.StatusCode(), expected)
		}
		if expected == http.StatusOK && (response.JSON200 == nil || response.JSON200.Id == nil || *response.JSON200.Id != id || response.JSON200.Status == nil || string(*response.JSON200.Status) != string(status)) {
			return fmt.Errorf("account status response did not match %s", status)
		}
		return nil
	}

	if err := change(accounts.FROZEN, http.StatusOK); err != nil {
		return BoundaryEvidence{}, err
	}
	tx, err := a.transactionsAPI()
	if err != nil {
		return BoundaryEvidence{}, err
	}
	movement := transactions.DepositRequest{AccountId: id, Amount: 8, Description: text("Lifecycle movement")}
	txEditor := transactions.RequestEditorFn(bearer(token))
	frozen, err := tx.DepositWithResponse(ctx, movement, txEditor)
	if err != nil {
		return BoundaryEvidence{}, fmt.Errorf("POST /api/v1/transactions/deposit request failed")
	}
	if frozen.StatusCode() != http.StatusConflict {
		return BoundaryEvidence{}, unexpectedStatus(http.MethodPost, "/api/v1/transactions/deposit", frozen.StatusCode(), http.StatusConflict)
	}
	if err := change(accounts.ACTIVE, http.StatusOK); err != nil {
		return BoundaryEvidence{}, err
	}
	deposited, err := tx.DepositWithResponse(ctx, movement, txEditor)
	if err != nil || deposited.StatusCode() != http.StatusCreated || deposited.JSON201 == nil {
		return BoundaryEvidence{}, fmt.Errorf("active account deposit did not settle")
	}
	deposit := ledgerFrom(*deposited.JSON201)
	if err := change(accounts.CLOSED, http.StatusConflict); err != nil {
		return BoundaryEvidence{}, err
	}
	withdrawn, err := tx.WithdrawWithResponse(ctx, transactions.WithdrawRequest{
		AccountId: id, Amount: 8, Description: text("Lifecycle movement"),
	}, txEditor)
	if err != nil || withdrawn.StatusCode() != http.StatusCreated || withdrawn.JSON201 == nil {
		return BoundaryEvidence{}, fmt.Errorf("withdrawal of the lifecycle deposit did not settle")
	}
	withdrawal := ledgerFrom(*withdrawn.JSON201)
	for range 2 {
		result, err := accountsClient.CloseAccountWithResponse(ctx, id, editor)
		if err != nil {
			return BoundaryEvidence{}, err
		}
		if result.StatusCode() != http.StatusNoContent || len(result.Body) != 0 {
			return BoundaryEvidence{}, fmt.Errorf("closing zero-balance account did not return empty HTTP 204")
		}
	}
	if err := change(accounts.ACTIVE, http.StatusConflict); err != nil {
		return BoundaryEvidence{}, err
	}
	var history transactions.TransactionList
	if err := call(ctx, a.Frontend, fmt.Sprintf("/api/v1/accounts/%d/transactions", id), token, nil, http.StatusOK, &history); err != nil {
		return BoundaryEvidence{}, err
	}
	if len(history) != 2 || ledgerFrom(history[0]).ID != withdrawal.ID || ledgerFrom(history[1]).ID != deposit.ID {
		return BoundaryEvidence{}, fmt.Errorf("closed account history did not retain both movements")
	}
	exported, err := accountsClient.ExportStatementWithResponse(ctx, id, editor)
	if err != nil {
		return BoundaryEvidence{}, fmt.Errorf("POST /api/v1/accounts/%d/export-statement request failed", id)
	}
	status, _, statement, err := statementFrom(exported.JSON200)
	if exported.StatusCode() != http.StatusOK || err != nil || status != "generated" || statement.AccountID != id || statement.Balance != 0 {
		return BoundaryEvidence{}, fmt.Errorf("closed account statement did not retain the zero balance")
	}
	return BoundaryEvidence{"Account lifecycle", "freeze, unfreeze, zero-balance closure and retained history",
		"frozen deposit and funded closure were rejected; repeated zero-balance closure returned HTTP 204 and history remained available"}, nil
}
