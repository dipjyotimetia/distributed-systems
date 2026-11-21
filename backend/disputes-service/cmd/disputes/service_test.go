package main

import (
	casesmodel "github.com/dipjyotimetia/distributed-systems/backend/disputes-service/internal/cases"
	"github.com/dipjyotimetia/distributed-systems/backend/disputes-service/internal/httpapi"
	"github.com/dipjyotimetia/distributed-systems/backend/disputes-service/internal/postgres"
	"github.com/dipjyotimetia/distributed-systems/backend/disputes-service/internal/temporal"

	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/golang-jwt/jwt/v5"
	enumspb "go.temporal.io/api/enums/v1"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
	"go.temporal.io/api/serviceerror"

	disputesv1 "github.com/dipjyotimetia/distributed-systems/backend/disputes-service/internal/generated/disputes/v1"
	"go.temporal.io/sdk/client"
)

var testKey = []byte("synthetic-test-signing-key-for-disputes")

func token(t *testing.T, method jwt.SigningMethod, identity string, expiry *jwt.NumericDate, key []byte) string {
	t.Helper()
	raw, err := jwt.NewWithClaims(method, struct {
		UserID string `json:"userId"`
		jwt.RegisteredClaims
	}{UserID: identity, RegisteredClaims: jwt.RegisteredClaims{
		ExpiresAt: expiry,
	}}).SignedString(key)
	if err != nil {
		t.Fatal(err)
	}
	return "Bearer " + raw
}

func signed(t *testing.T, id string) string {
	return token(t, jwt.SigningMethodHS256, id, jwt.NewNumericDate(time.Now().Add(time.Hour)), testKey)
}

func call(handler http.Handler, method, path, auth, body string) *httptest.ResponseRecorder {
	request := httptest.NewRequest(method, path, strings.NewReader(body))
	request.Header.Set("Authorization", auth)
	request.Header.Set("X-User-Id", "8")
	request.Header.Set("Idempotency-Key", "test-command")
	response := httptest.NewRecorder()
	handler.ServeHTTP(response, request)
	return response
}

func expect(t *testing.T, response *httptest.ResponseRecorder, status int) {
	t.Helper()
	if response.Code != status {
		t.Fatalf("status %d, expected %d, body %s", response.Code, status, response.Body)
	}
}

func parse[T any](t *testing.T, response *httptest.ResponseRecorder) T {
	t.Helper()
	var body T
	if err := json.Unmarshal(response.Body.Bytes(), &body); err != nil {
		t.Fatal(err)
	}
	return body
}

type workflowStartFunc func(context.Context, client.StartWorkflowOptions, interface{}, ...interface{}) (client.WorkflowRun, error)

func (f workflowStartFunc) ExecuteWorkflow(ctx context.Context, options client.StartWorkflowOptions, workflow interface{}, args ...interface{}) (client.WorkflowRun, error) {
	return f(ctx, options, workflow, args...)
}

type lifecycleTransfers struct{ customer string }

func (s lifecycleTransfers) Lookup(_ context.Context, authorization string, id int64) (httpapi.TransferView, error) {
	switch id {
	case 18:
		return httpapi.TransferView{}, status.Error(codes.NotFound, "missing")
	case 19:
		return httpapi.TransferView{ID: 19, UserID: 7, Type: "TRANSFER", Status: "PENDING"}, nil
	case 20:
		return httpapi.TransferView{}, status.Error(codes.Unavailable, "down")
	default:
		if authorization != s.customer {
			return httpapi.TransferView{}, status.Error(codes.NotFound, "missing")
		}
		return httpapi.TransferView{ID: id, UserID: 7, Type: "TRANSFER", Status: "COMPLETED"}, nil
	}
}

func TestDisputeLifecycle(t *testing.T) {
	dsn := os.Getenv("DISPUTES_TEST_DATABASE_URL")
	if dsn == "" {
		t.Skip("DISPUTES_TEST_DATABASE_URL is required for PostgreSQL tests")
	}
	db, err := sql.Open("pgx", dsn)
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	ctx := context.Background()
	migrateTestDatabase(t, db)
	customer, stranger, operator := signed(t, "7"), signed(t, "9"), signed(t, "8")
	s := postgres.New(db)
	const delay = 24 * time.Hour
	a := httpapi.New(db, s, testKey, map[int64]bool{8: true}, lifecycleTransfers{customer: customer}, delay)
	routes := a.Routes()
	const cases = "/api/disputes"
	const queue = "/api/operations/disputes"
	expect(t, call(routes, "GET", "/health", "", ""), 200)
	expect(t, call(routes, "GET", queue+"?page=-1", operator, ""), 400)
	expect(t, call(routes, "GET", cases+"/bad", customer, ""), 404)
	expect(t, call(routes, "POST", queue+"/bad/resolve", operator, "{}"), 404)
	for _, body := range []string{`{}`, `{"transferId":17,"reason":" "}`, `{"transferId":17,"reason":"x","extra":42}`, `{"transferId":17,"reason":"x"} true`} {
		expect(t, call(routes, "POST", cases, customer, body), 400)
	}
	for id, status := range map[int]int{18: 404, 19: 422, 20: 502} {
		expect(t, call(routes, "POST", cases, customer, fmt.Sprintf(`{"transferId":%d,"reason":"Check"}`, id)), status)
	}

	// Use unique transfers so repeated runs against the same disposable DB can be cleaned up safely.
	firstTransfer := time.Now().UnixNano()
	t.Cleanup(func() {
		cleanup, err := sql.Open("pgx", dsn)
		if err != nil {
			t.Error(err)
			return
		}
		defer cleanup.Close()
		_, err = cleanup.ExecContext(ctx, "DELETE FROM disputes.workflow_outbox WHERE case_id IN (SELECT id FROM disputes.cases WHERE transfer_id BETWEEN $1 AND $2)", firstTransfer, firstTransfer+20)
		if err == nil {
			_, err = cleanup.ExecContext(ctx, "DELETE FROM disputes.cases WHERE transfer_id BETWEEN $1 AND $2", firstTransfer, firstTransfer+20)
		}
		if err != nil {
			t.Error(err)
		}
	})
	body := fmt.Sprintf(`{"transferId":%d,"reason":"Check this transfer"}`, firstTransfer)
	responses := make(chan *httptest.ResponseRecorder, 2)
	for range 2 {
		go func() { responses <- call(routes, "POST", cases, customer, body) }()
	}
	first, second := <-responses, <-responses
	if first.Code > second.Code {
		first, second = second, first
	}
	expect(t, first, 200)
	expect(t, second, 201)
	c := parse[casesmodel.Case](t, second)
	if duplicates := parse[casesmodel.Case](t, first); duplicates.ID != c.ID || c.Status != "OPEN" {
		t.Fatalf("duplicate did not return the open case: %+v, %+v", c, duplicates)
	}
	replayed, created, err := s.Create(ctx, 7, firstTransfer, "Different reason", delay)
	if err != nil || created || replayed.ID != c.ID || replayed.Reason != c.Reason {
		t.Fatalf("database uniqueness did not preserve the initial case: %+v, %v", replayed, err)
	}
	casePath := fmt.Sprintf("%s/%d", cases, c.ID)
	expect(t, call(routes, "POST", cases, stranger, body), 404)
	expect(t, call(routes, "GET", casePath, stranger, ""), 404)
	expect(t, call(routes, "GET", casePath, customer, ""), 200)
	if items := parse[[]casesmodel.Case](t, call(routes, "GET", cases, stranger, "")); len(items) != 0 {
		t.Fatal("customer list exposed another customer's case")
	}

	unavailable := errors.New("Temporal unavailable")
	startCount := 0
	starter := workflowStartFunc(func(_ context.Context, opts client.StartWorkflowOptions, _ interface{}, args ...interface{}) (client.WorkflowRun, error) {
		startCount++
		input := args[0].(*disputesv1.CaseRevision)
		if input.GetCaseId() != c.ID || input.GetRevision() != 0 ||
			opts.ID != fmt.Sprintf("dispute-%d", c.ID) || opts.TaskQueue != temporal.TaskQueue ||
			opts.WorkflowIDReusePolicy != enumspb.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE ||
			!opts.WorkflowExecutionErrorWhenAlreadyStarted {
			t.Errorf("unexpected workflow start %v %v", opts, input)
		}
		if startCount == 1 {
			return nil, unavailable
		}
		return nil, &serviceerror.WorkflowExecutionAlreadyStarted{}
	})
	if err := temporal.Dispatch(ctx, db, starter); !errors.Is(err, unavailable) {
		t.Fatalf("expected temporary error, got %v", err)
	}
	expect(t, call(routes, "GET", casePath, customer, ""), 200)
	for range 2 {
		if err := temporal.Dispatch(ctx, db, starter); err != nil {
			t.Fatal(err)
		}
	}
	if startCount != 2 {
		t.Fatalf("dispatch called Temporal %d times", startCount)
	}

	for i := int64(1); i <= 20; i++ {
		if _, _, err := s.Create(ctx, 7, firstTransfer+i, "Additional case", delay); err != nil {
			t.Fatal(err)
		}
	}
	if err := s.Escalate(ctx, c.ID); err != nil {
		t.Fatal(err)
	}
	type queuePage struct {
		Items   []casesmodel.Case
		HasMore bool
	}
	result := parse[queuePage](t, call(routes, "GET", queue, operator, ""))
	if len(result.Items) != 20 || !result.HasMore || result.Items[0].ID != c.ID || result.Items[0].Status != "ESCALATED" {
		t.Fatalf("unexpected queue page: %+v", result)
	}
	next := parse[queuePage](t, call(routes, "GET", queue+"?page=2", operator, ""))
	if len(next.Items) != 1 || next.HasMore {
		t.Fatalf("unexpected second page: %+v", next)
	}

	requestPath := fmt.Sprintf("%s/%d/evidence-requests", queue, c.ID)
	responsePath := fmt.Sprintf("%s/evidence-requests/%d/response", casePath, c.ID)
	const prompt = `{"text":"Identify the recipient"}`
	const answer = `{"text":"I know the recipient"}`
	expect(t, call(routes, "POST", requestPath, customer, prompt), 403)
	expect(t, call(routes, "POST", requestPath, operator, `{"text":" "}`), 400)
	requested := call(routes, "POST", requestPath, operator, prompt)
	expect(t, requested, 200)
	evidence := parse[casesmodel.Case](t, requested)
	if evidence.InvestigationStage != "WAITING_FOR_EVIDENCE" || evidence.Evidence.Deadline.Sub(evidence.Evidence.RequestedAt) != 7*24*time.Hour {
		t.Fatalf("unexpected evidence request: %+v", evidence)
	}
	expect(t, call(routes, "POST", requestPath, operator, prompt), 200)
	expect(t, call(routes, "POST", requestPath, operator, `{"text":"Different question"}`), 409)
	expect(t, call(routes, "POST", responsePath, stranger, answer), 404)
	expect(t, call(routes, "POST", responsePath, customer, answer), 200)
	expect(t, call(routes, "POST", responsePath, customer, answer), 200)
	expect(t, call(routes, "POST", responsePath, customer, `{"text":"Different answer"}`), 409)
	if read := parse[casesmodel.Case](t, call(routes, "GET", casePath, customer, "")); read.InvestigationStage != "OPERATOR_REVIEW" ||
		read.Evidence.Response == nil || *read.Evidence.Response != "I know the recipient" {
		t.Fatalf("unexpected response: %+v", read)
	}

	timeout := next.Items[0]
	requestPath = fmt.Sprintf("%s/%d/evidence-requests", queue, timeout.ID)
	responsePath = fmt.Sprintf("%s/%d/evidence-requests/%d/response", cases, timeout.ID, timeout.ID)
	expect(t, call(routes, "POST", requestPath, operator, prompt), 200)
	if _, err := db.ExecContext(ctx, "UPDATE disputes.cases SET evidence_deadline=now()-interval '1 second' WHERE id=$1", timeout.ID); err != nil {
		t.Fatal(err)
	}
	expect(t, call(routes, "POST", responsePath, customer, answer), 409)
	advanced := make(chan error, 1)
	go func() { _, _, err := s.Advance(ctx, timeout.ID); advanced <- err }()
	if _, err := s.Resolve(ctx, timeout.ID, 8, "Resolved during timeout"); err != nil {
		t.Fatal(err)
	}
	if err := <-advanced; err != nil {
		t.Fatal(err)
	}
	if resolved, err := s.ByID(ctx, 7, timeout.ID); err != nil || resolved.Status != "RESOLVED" || resolved.Evidence.ClosedAt == nil {
		t.Fatalf("timeout changed resolved case: %+v %v", resolved, err)
	}

	overdue := result.Items[1]
	if _, err := s.RequestEvidence(ctx, overdue.ID, 8, "overdue", "Provide a written response"); err != nil {
		t.Fatal(err)
	}
	if _, err := db.ExecContext(ctx, "UPDATE disputes.cases SET evidence_deadline=now()-interval '1 second' WHERE id=$1", overdue.ID); err != nil {
		t.Fatal(err)
	}
	if ended, _, err := s.Advance(ctx, overdue.ID); ended || err != nil {
		t.Fatalf("overdue investigation ended=%t error=%v", ended, err)
	}
	if review, err := s.ByID(ctx, 7, overdue.ID); err != nil || review.InvestigationStage != "OPERATOR_REVIEW" ||
		review.Status != "OPEN" || review.Evidence.Response != nil || review.Evidence.ClosedAt == nil {
		t.Fatalf("overdue request did not return to review: %+v %v", review, err)
	}

	resolvePath := fmt.Sprintf("%s/%d/resolve", queue, c.ID)
	expect(t, call(routes, "POST", resolvePath, operator, `{}`), 400)
	const summary = `{"resolution":"Transfer reviewed"}`
	var concurrent sync.WaitGroup
	for range 2 {
		concurrent.Add(1)
		go func() {
			defer concurrent.Done()
			responses <- call(routes, "POST", resolvePath, operator, summary)
		}()
	}
	concurrent.Wait()
	for range 2 {
		expect(t, <-responses, 200)
	}
	expect(t, call(routes, "POST", resolvePath, operator, `{"resolution":"Different decision"}`), 409)
	if err := s.Escalate(ctx, c.ID); err != nil {
		t.Fatal(err)
	}
	resolved, err := s.ByID(ctx, 7, c.ID)
	if err != nil || resolved.Status != "RESOLVED" || resolved.EscalatedAt == nil || resolved.ResolvedAt == nil ||
		resolved.ResolvedBy == nil || *resolved.ResolvedBy != 8 || resolved.Resolution == nil || *resolved.Resolution != "Transfer reviewed" {
		t.Fatalf("unexpected resolved case: %+v, %v", resolved, err)
	}
	visible := parse[[]casesmodel.Case](t, call(routes, "GET", cases, customer, ""))
	if len(visible) != 21 || visible[20].Status != "RESOLVED" {
		t.Fatal("resolved case missing on customer list")
	}
}

func TestRuntime(t *testing.T) {
	dsn := os.Getenv("DISPUTES_TEST_DATABASE_URL")
	if dsn == "" {
		t.Skip("DISPUTES_TEST_DATABASE_URL is required for runtime tests")
	}
	db, err := sql.Open("pgx", dsn)
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	migrateTestDatabase(t, db)
	t.Setenv("DATABASE_URL", dsn)
	t.Setenv("JWT_SECRET", string(testKey))
	t.Setenv("TRANSACTIONS_GRPC_ADDRESS", "transactions-service:9095")
	t.Setenv("DISPUTES_OPERATOR_IDS", "8")
	t.Setenv("OTEL_EXPORTER_OTLP_ENDPOINT", "")
	t.Setenv("DISPUTES_TEST_ESCALATION_DELAY", "")
	if err := run(context.Background(), "invalid"); err == nil {
		t.Fatal("invalid mode accepted")
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	done := make(chan error, 1)
	go func() { done <- run(ctx, "api") }()
	probe := &http.Client{Timeout: time.Second}
	waitUntil(t, func() bool {
		res, err := probe.Get("http://localhost:8080/health")
		if err != nil {
			return false
		}
		defer res.Body.Close()
		return res.StatusCode == http.StatusOK
	})
	cancel()
	if err := <-done; err != nil {
		t.Fatal(err)
	}

	db.Close()
	expect(t, call(httpapi.HealthRoutes(db), "GET", "/health", "", ""), 503)
	expect(t, call(httpapi.New(db, postgres.New(db), testKey, nil, nil, 0).Routes(), "GET", "/api/disputes", signed(t, "7"), ""), 500)
}

func waitUntil(t *testing.T, check func() bool) {
	t.Helper()
	deadline := time.Now().Add(15 * time.Second)
	for !check() {
		if time.Now().After(deadline) {
			t.Fatal("condition not reached")
		}
		time.Sleep(50 * time.Millisecond)
	}
}

func TestWorkerDispatchesAndEscalates(t *testing.T) {
	dsn, address := os.Getenv("DISPUTES_TEST_DATABASE_URL"), os.Getenv("DISPUTES_TEST_TEMPORAL_ADDRESS")
	if dsn == "" || address == "" {
		t.Skip("PostgreSQL and Temporal are required for worker runtime tests")
	}
	t.Setenv("DATABASE_URL", dsn)
	t.Setenv("TEMPORAL_ADDRESS", address)
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	db, err := sql.Open("pgx", dsn)
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	migrateTestDatabase(t, db)
	s := postgres.New(db)
	item, created, err := s.Create(ctx, 7, time.Now().UnixNano(), "Check this transfer", time.Second)
	if err != nil || !created {
		t.Fatalf("create case: %v", err)
	}
	defer func() {
		cleanup, cancel := context.WithTimeout(context.Background(), time.Second)
		defer cancel()
		if _, err := db.ExecContext(cleanup, "DELETE FROM disputes.workflow_outbox WHERE case_id=$1", item.ID); err != nil {
			t.Error(err)
		}
		if _, err := db.ExecContext(cleanup, "DELETE FROM disputes.cases WHERE id=$1", item.ID); err != nil {
			t.Error(err)
		}
	}()

	done := make(chan error, 1)
	go func() { done <- run(ctx, "worker") }()
	waitUntil(t, func() bool {
		item, err := s.ByID(ctx, 7, item.ID)
		return err == nil && item.Status == "ESCALATED" && item.EscalatedAt != nil
	})
	if _, err := s.RequestEvidence(ctx, item.ID, 8, "ask", "Review this transfer"); err != nil {
		t.Fatal(err)
	}
	if _, err := s.RespondToEvidence(ctx, item.ID, 7, fmt.Sprint(item.ID), "answer", "I recognize it"); err != nil {
		t.Fatal(err)
	}
	if _, err := s.Resolve(ctx, item.ID, 8, "Reviewed"); err != nil {
		t.Fatal(err)
	}
	waitUntil(t, func() bool {
		var pending int
		return db.QueryRowContext(ctx, "SELECT count(*) FROM disputes.signal_outbox WHERE case_id=$1", item.ID).Scan(&pending) == nil && pending == 0
	})
	waitUntil(t, func() bool {
		res, err := (&http.Client{Timeout: time.Second}).Get("http://localhost:8080/health")
		if err != nil {
			return false
		}
		defer res.Body.Close()
		return res.StatusCode == 200
	})

	cancel()
	if err := <-done; err != nil {
		t.Fatal(err)
	}
}

func migrateTestDatabase(t *testing.T, db *sql.DB) {
	t.Helper()
	schema, err := os.ReadFile("../../../../kubernetes/overlays/full/schema.sql")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := db.Exec("CREATE SCHEMA IF NOT EXISTS disputes;" + string(schema)); err != nil {
		t.Fatal(err)
	}
}

func TestEscalationDelay(t *testing.T) {
	for input, want := range map[string]time.Duration{"": 24 * time.Hour, "15s": 15 * time.Second, "0s": 0, "bad": 0} {
		delay, err := escalationDelay(input)
		if delay != want || (err != nil) != (want == 0) {
			t.Fatalf("delay %q gave %s, %v", input, delay, err)
		}
	}
}

func TestAiDraftInternalAPI(t *testing.T) {
	dsn := os.Getenv("DISPUTES_TEST_DATABASE_URL")
	if dsn == "" {
		t.Skip("DISPUTES_TEST_DATABASE_URL is required for PostgreSQL tests")
	}
	db, err := sql.Open("pgx", dsn)
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	ctx := context.Background()
	migrateTestDatabase(t, db)
	store := postgres.New(db)
	const token = "test-internal-token"
	a := httpapi.NewWithInternal(db, store, testKey, map[int64]bool{8: true}, nil, time.Hour, token)

	c, created, err := store.Create(ctx, 7, time.Now().UnixNano(), "Need AI draft", time.Hour)
	if err != nil || !created {
		t.Fatalf("create: %+v %v", c, err)
	}
	t.Cleanup(func() {
		_, _ = db.ExecContext(ctx, "DELETE FROM disputes.signal_outbox WHERE case_id=$1", c.ID)
		_, _ = db.ExecContext(ctx, "DELETE FROM disputes.workflow_outbox WHERE case_id=$1", c.ID)
		_, _ = db.ExecContext(ctx, "DELETE FROM disputes.cases WHERE id=$1", c.ID)
	})
	if err := store.Escalate(ctx, c.ID); err != nil {
		t.Fatal(err)
	}
	needed, err := store.NeedsAiDraft(ctx, c.ID)
	if err != nil || !needed {
		t.Fatalf("NeedsAiDraft=%v %v", needed, err)
	}

	if _, err := a.ResearchContext(ctx, "", c.ID); !errors.Is(err, httpapi.ErrInternalAuth) {
		t.Fatalf("missing token: %v", err)
	}
	loaded, err := a.ResearchContext(ctx, token, c.ID)
	if err != nil || loaded.ID != c.ID || loaded.Status != "ESCALATED" {
		t.Fatalf("unexpected context: %+v %v", loaded, err)
	}
	if _, err := a.ResearchDraft(ctx, token, c.ID, json.RawMessage(`{}`)); err == nil {
		t.Fatal("empty draft accepted")
	}
	saved, err := a.ResearchDraft(ctx, token, c.ID, json.RawMessage(`{"reasonCode":"UNAUTHORIZED","summary":"Looks fraudulent","recommendedAction":"REQUEST_EVIDENCE","confidence":0.9}`))
	if err != nil || saved.AiDraft == nil {
		t.Fatalf("expected ai draft, %+v %v", saved, err)
	}
	needed, err = store.NeedsAiDraft(ctx, c.ID)
	if err != nil || needed {
		t.Fatalf("draft should clear NeedsAiDraft: %v %v", needed, err)
	}
	var pending int
	if err := db.QueryRowContext(ctx, "SELECT count(*) FROM disputes.signal_outbox WHERE case_id=$1", c.ID).Scan(&pending); err != nil || pending != 1 {
		t.Fatalf("expected signal outbox row, pending=%d err=%v", pending, err)
	}

	if _, err := store.Resolve(ctx, c.ID, 8, "Closed after draft"); err != nil {
		t.Fatal(err)
	}
	_, err = store.SaveAiDraft(ctx, c.ID, json.RawMessage(`{"summary":"late"}`))
	if !errors.Is(err, casesmodel.ErrConflict) {
		t.Fatalf("expected conflict after resolve, got %v", err)
	}
}
