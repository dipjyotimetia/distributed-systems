package integration

import (
	"context"
	"crypto/rand"
	"fmt"
	"net/http"
	"os"
	"os/exec"
	"strings"
	"testing"
	"time"

	"microsvc/integration/temporal/openapi/accounts"
	"microsvc/integration/temporal/openapi/transactions"
)

func TestVersionedMovements(t *testing.T) {
	frontend, gateway := os.Getenv("MICROSVC_FRONTEND"), os.Getenv("MICROSVC_GATEWAY")
	fraud, worker := os.Getenv("MICROSVC_FRAUD_CONTAINER"), os.Getenv("MICROSVC_TRANSACTIONS_CONTAINER")
	if frontend == "" || gateway == "" || fraud == "" || worker == "" {
		t.Skip("run the ledger Compose scenario")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()
	activity := &Activities{Frontend: frontend, Password: "T3st!" + rand.Text()}
	input := Input{User: "moves" + strings.ToLower(rand.Text()[:16])}
	if _, err := activity.Register(ctx, input); err != nil {
		t.Fatal(err)
	}
	account, err := activity.CreateAccount(ctx, input, "CHECKING", 100)
	if err != nil {
		t.Fatal(err)
	}
	token, err := activity.login(ctx, input)
	if err != nil {
		t.Fatal(err)
	}

	accts, err := activity.accountsAPI()
	if err != nil {
		t.Fatal(err)
	}
	movements, err := activity.transactionsAPI()
	if err != nil {
		t.Fatal(err)
	}
	direct, err := transactions.NewClientWithResponses(gateway)
	if err != nil {
		t.Fatal(err)
	}
	editor := transactions.RequestEditorFn(bearer(token))
	docker := func(command, container string) error {
		return exec.CommandContext(ctx, "docker", command, container).Run()
	}
	if err := docker("pause", fraud); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = exec.Command("docker", "unpause", fraud).Run() })

	params := &transactions.SubmitMovementParams{IdempotencyKey: rand.Text()}
	intent := transactions.MovementIntent{
		Type: transactions.MovementIntentTypeWITHDRAWAL, FromAccountId: &account,
		Money: transactions.MovementMoney{Currency: "USD", Amount: "25.50"},
	}
	accepted, err := movements.SubmitMovementWithResponse(ctx, params, intent, editor)
	if err != nil || accepted.StatusCode() != http.StatusAccepted || accepted.JSON202 == nil {
		t.Fatalf("frontend acceptance failed: %v", err)
	}
	id := accepted.JSON202.Id
	if location := accepted.HTTPResponse.Header.Get("Location"); location != "/api/v1/movements/"+id.String() {
		t.Fatalf("unexpected movement location %q", location)
	}
	retried, err := direct.SubmitMovementWithResponse(ctx, params, intent, editor)
	if err != nil || retried.JSON202 == nil || retried.JSON202.Id != id {
		t.Fatalf("gateway retry did not return the accepted movement: %v", err)
	}
	conflict := intent
	conflict.Money.Amount = "25.51"
	changed, err := movements.SubmitMovementWithResponse(ctx, params, conflict, editor)
	if err != nil || changed.StatusCode() != http.StatusConflict || changed.JSON409 == nil ||
		changed.JSON409.Code != "IDEMPOTENCY_KEY_REUSED" {
		t.Fatalf("different payload with the same key was not rejected: %v", err)
	}
	waitFor(t, ctx, func(ctx context.Context) bool {
		result, err := movements.GetMovementWithResponse(ctx, id, editor)
		return err == nil && result.JSON200 != nil && result.JSON200.Status == transactions.PENDING &&
			result.JSON200.ReasonCode != nil && *result.JSON200.ReasonCode == "SCREENING_UNAVAILABLE"
	})
	if err := docker("restart", worker); err != nil {
		t.Fatal(err)
	}
	if err := docker("unpause", fraud); err != nil {
		t.Fatal(err)
	}
	waitFor(t, ctx, func(ctx context.Context) bool {
		result, err := movements.GetMovementWithResponse(ctx, id, editor)
		return err == nil && result.JSON200 != nil && result.JSON200.Status == transactions.POSTED
	})
	balance, err := accts.GetExactBalancesWithResponse(ctx, account, accounts.RequestEditorFn(bearer(token)))
	if err != nil || balance.JSON200 == nil || balance.JSON200.PostedBalance != "74.50" ||
		balance.JSON200.HeldAmount != "0.00" || balance.JSON200.AvailableBalance != "74.50" {
		t.Fatalf("posted response did not match the durable exact balance: %v", err)
	}
	t.Logf("recovered one posted movement on account %d after service replacement", account)

	statement := fmt.Sprintf("SELECT (SELECT count(*) FROM accounts_service.account_postings WHERE operation_id = '%[1]s')"+
		" || '|' || (SELECT count(*) FROM transactions_service.event_outbox WHERE movement_id = '%[1]s' "+
		"AND payload::jsonb->>'status' = 'POSTED')", id)
	records, err := exec.CommandContext(ctx, "docker", "exec", os.Getenv("MICROSVC_POSTGRES_CONTAINER"),
		"psql", "-Atq", "-U", "postgres", "-d", "banking_app", "-c", statement).Output()
	if err != nil || strings.TrimSpace(string(records)) != "1|1" {
		t.Fatalf("expected one durable posting and one logical posted event, got %q: %v", records, err)
	}
}
