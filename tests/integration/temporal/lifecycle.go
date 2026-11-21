package integration

import (
	"context"
	"fmt"
	"net/http"

	banking "microsvc/integration/temporal/gen"
)

func (a *Activities) VerifyAccountLifecycle(ctx context.Context, input Input, id int64) (BoundaryEvidence, error) {
	token, err := a.login(ctx, input)
	if err != nil {
		return BoundaryEvidence{}, err
	}
	otherToken, err := a.login(ctx, Input{User: input.User + "b"})
	if err != nil {
		return BoundaryEvidence{}, err
	}
	other, err := a.api(otherToken)
	if err != nil {
		return BoundaryEvidence{}, err
	}
	foreign, err := other.CloseAccountWithResponse(ctx, id)
	if err != nil || foreign.StatusCode() != http.StatusNotFound {
		return BoundaryEvidence{}, fmt.Errorf("foreign account closure must return HTTP 404")
	}
	client, err := a.api(token)
	if err != nil {
		return BoundaryEvidence{}, err
	}
	change := func(status banking.AccountStatusChange, expected int) error {
		response, err := client.UpdateAccountStatusWithResponse(ctx, id, banking.StatusUpdateRequest{Status: status})
		if err != nil {
			return err
		}
		if response.StatusCode() != expected {
			return fmt.Errorf("account status %s returned %d, expected %d", status, response.StatusCode(), expected)
		}
		if expected == http.StatusOK && (response.JSON200 == nil || response.JSON200.Id != id || string(response.JSON200.Status) != string(status)) {
			return fmt.Errorf("account status response did not match %s", status)
		}
		return nil
	}

	if err := change(banking.AccountStatusChangeFROZEN, http.StatusOK); err != nil {
		return BoundaryEvidence{}, err
	}
	movement := banking.DepositRequest{AccountId: id, Amount: 8, Description: strPtr("Lifecycle movement")}
	frozen, err := client.DepositWithResponse(ctx, nil, movement)
	if err != nil || frozen.StatusCode() != http.StatusConflict {
		return BoundaryEvidence{}, fmt.Errorf("frozen deposit must return HTTP 409")
	}
	if err := change(banking.AccountStatusChangeACTIVE, http.StatusOK); err != nil {
		return BoundaryEvidence{}, err
	}
	deposited, err := client.DepositWithResponse(ctx, nil, movement)
	if err != nil || deposited.StatusCode() != http.StatusCreated || deposited.JSON201 == nil {
		return BoundaryEvidence{}, fmt.Errorf("lifecycle deposit did not settle")
	}
	deposit := ledgerFrom(*deposited.JSON201)
	if err := change(banking.AccountStatusChangeCLOSED, http.StatusConflict); err != nil {
		return BoundaryEvidence{}, err
	}
	withdrawn, err := client.WithdrawWithResponse(ctx, nil, banking.WithdrawRequest{AccountId: id, Amount: 8, Description: movement.Description})
	if err != nil || withdrawn.StatusCode() != http.StatusCreated || withdrawn.JSON201 == nil {
		return BoundaryEvidence{}, fmt.Errorf("lifecycle withdrawal did not settle")
	}
	withdrawal := ledgerFrom(*withdrawn.JSON201)
	for range 2 {
		result, err := client.CloseAccountWithResponse(ctx, id)
		if err != nil {
			return BoundaryEvidence{}, err
		}
		if result.StatusCode() != http.StatusNoContent || len(result.Body) != 0 {
			return BoundaryEvidence{}, fmt.Errorf("closing zero-balance account did not return empty HTTP 204")
		}
	}
	if err := change(banking.AccountStatusChangeACTIVE, http.StatusConflict); err != nil {
		return BoundaryEvidence{}, err
	}
	historyResponse, err := client.ListAccountTransactionsWithResponse(ctx, id, nil)
	if err != nil {
		return BoundaryEvidence{}, err
	}
	history, err := transactionRows(historyResponse.Body)
	if err != nil || historyResponse.StatusCode() != http.StatusOK || len(history) != 2 || history[0].Id != withdrawal.ID || history[1].Id != deposit.ID {
		return BoundaryEvidence{}, fmt.Errorf("closed account history did not retain both movements")
	}
	export, err := client.ExportAccountStatementWithResponse(ctx, id)
	if err != nil || export.StatusCode() != http.StatusOK || export.JSON200 == nil || export.JSON200.Status != banking.Generated || export.JSON200.Statement == nil || export.JSON200.Statement.AccountId != id || export.JSON200.Statement.Balance != 0 {
		return BoundaryEvidence{}, fmt.Errorf("closed account statement did not retain the zero balance")
	}
	return BoundaryEvidence{"Account lifecycle", "freeze, unfreeze, zero-balance closure and retained history",
		"frozen deposit and funded closure were rejected; repeated zero-balance closure returned HTTP 204 and history remained available"}, nil
}
