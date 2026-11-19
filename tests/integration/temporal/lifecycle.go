package integration

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
)

func (a *Activities) VerifyAccountLifecycle(ctx context.Context, input Input, id int64) (BoundaryEvidence, error) {
	token, err := a.login(ctx, input)
	if err != nil {
		return BoundaryEvidence{}, err
	}
	path := fmt.Sprintf("/api/accounts/%d", id)
	other, err := a.login(ctx, Input{User: input.User + "b"})
	if err != nil {
		return BoundaryEvidence{}, err
	}
	foreign, err := request(ctx, http.MethodDelete, a.Frontend, path, other, nil)
	if err != nil || foreign.Status != 404 {
		return BoundaryEvidence{}, fmt.Errorf("foreign account closure must return HTTP 404")
	}
	change := func(status string, expected int) error {
		response, err := request(ctx, http.MethodPatch, a.Frontend, path+"/status", token, map[string]string{"status": status})
		if err != nil {
			return err
		}
		if response.Status != expected {
			return fmt.Errorf("account status %s returned %d, expected %d", status, response.Status, expected)
		}
		if expected == 200 {
			var account accountRecord
			if json.Unmarshal(response.Body, &account) != nil || account.ID != id || account.Status != status {
				return fmt.Errorf("account status response did not match %s", status)
			}
		}
		return nil
	}

	if err := change("FROZEN", 200); err != nil {
		return BoundaryEvidence{}, err
	}
	payload := map[string]any{"accountId": id, "amount": 8, "description": "Lifecycle movement"}
	if err := call(ctx, a.Frontend, "/api/transactions/deposit", token, payload, 409, nil); err != nil {
		return BoundaryEvidence{}, err
	}
	if err := change("ACTIVE", 200); err != nil {
		return BoundaryEvidence{}, err
	}
	var deposit, withdrawal ledgerEntry
	if err := call(ctx, a.Frontend, "/api/transactions/deposit", token, payload, 201, &deposit); err != nil {
		return BoundaryEvidence{}, err
	}
	if err := change("CLOSED", 409); err != nil {
		return BoundaryEvidence{}, err
	}
	if err := call(ctx, a.Frontend, "/api/transactions/withdraw", token, payload, 201, &withdrawal); err != nil {
		return BoundaryEvidence{}, err
	}
	for range 2 {
		result, err := request(ctx, http.MethodDelete, a.Frontend, path, token, nil)
		if err != nil {
			return BoundaryEvidence{}, err
		}
		if result.Status != 204 || len(result.Body) != 0 {
			return BoundaryEvidence{}, fmt.Errorf("closing zero-balance account did not return empty HTTP 204")
		}
	}
	if err := change("ACTIVE", 409); err != nil {
		return BoundaryEvidence{}, err
	}
	var history []ledgerEntry
	if err := call(ctx, a.Frontend, path+"/transactions", token, nil, 200, &history); err != nil {
		return BoundaryEvidence{}, err
	}
	if len(history) != 2 || history[0].ID != withdrawal.ID || history[1].ID != deposit.ID {
		return BoundaryEvidence{}, fmt.Errorf("closed account history did not retain both movements")
	}
	var export struct {
		Status    string
		Statement accountStatement
	}
	if err := call(ctx, a.Frontend, path+"/export-statement", token, struct{}{}, 200, &export); err != nil {
		return BoundaryEvidence{}, err
	}
	if export.Status != "generated" || export.Statement.AccountID != id || export.Statement.Balance != 0 {
		return BoundaryEvidence{}, fmt.Errorf("closed account statement did not retain the zero balance")
	}
	return BoundaryEvidence{"Account lifecycle", "freeze, unfreeze, zero-balance closure and retained history",
		"frozen deposit and funded closure were rejected; repeated zero-balance closure returned HTTP 204 and history remained available"}, nil
}
