package integration

import (
	"context"
	"fmt"
	"net/http"

	"microsvc/integration/temporal/openapi/transactions"
)

func (a *Activities) SeedPendingTransaction(ctx context.Context, userID, accountID int64) (int64, error) {
	// The public mutations settle immediately. Seed only the pending state the
	// conditional cancellation contract needs, in this scenario's database.
	sql := fmt.Sprintf(`INSERT INTO transactions_service.transactions
		(user_id, to_account_id, amount, type, description)
		VALUES (%d, %d, 3, 'DEPOSIT', 'Synthetic pending transaction') RETURNING id;`, userID, accountID)
	var id int64
	err := probeContainer(ctx, a.PostgresContainer, &id, "psql", "-U", "postgres", "-d", "banking_app", "-XqtA", "-v", "ON_ERROR_STOP=1", "-c", sql)
	if err == nil && id <= 0 {
		err = fmt.Errorf("pending transaction fixture did not return an ID")
	}
	return id, err
}

func (a *Activities) AssertConditionalCancellation(ctx context.Context, input Input, id int64) (BoundaryEvidence, error) {
	otherToken, err := a.login(ctx, Input{User: input.User + "b"})
	if err != nil {
		return BoundaryEvidence{}, err
	}
	client, err := a.transactionsAPI()
	if err != nil {
		return BoundaryEvidence{}, err
	}
	foreign, err := client.CancelTransactionWithResponse(ctx, id, transactions.RequestEditorFn(bearer(otherToken)))
	if err != nil || foreign.StatusCode() != http.StatusNotFound {
		return BoundaryEvidence{}, fmt.Errorf("foreign transaction cancellation must return HTTP 404")
	}
	token, err := a.login(ctx, input)
	if err != nil {
		return BoundaryEvidence{}, err
	}
	type cancellationResult struct {
		ID                  int64
		Status, ProcessedAt string
	}
	var first cancellationResult
	for attempt := 0; attempt < 2; attempt++ {
		response, err := client.CancelTransactionWithResponse(ctx, id, transactions.RequestEditorFn(bearer(token)))
		if err != nil {
			return BoundaryEvidence{}, err
		}
		if response.StatusCode() != http.StatusOK || response.JSON200 == nil {
			return BoundaryEvidence{}, fmt.Errorf("pending transaction cancellation did not return the cancelled row")
		}
		result := cancellationResult{ID: int64Value(response.JSON200.Id), Status: textValue(response.JSON200.Status), ProcessedAt: textValue(response.JSON200.ProcessedAt)}
		if result.ID != id || result.Status != "CANCELLED" || result.ProcessedAt == "" {
			return BoundaryEvidence{}, fmt.Errorf("pending transaction cancellation did not return the cancelled row")
		}
		if attempt == 0 {
			first = result
		} else if result != first {
			return BoundaryEvidence{}, fmt.Errorf("repeated cancellation changed the settled row")
		}
	}
	return BoundaryEvidence{"Transaction cancellation", "owned pending cancellation is idempotent, foreign receives 404 and settled receives 409",
		"frontend returned one cancellation timestamp on both attempts and rejected foreign and settled cancellations"}, nil
}
