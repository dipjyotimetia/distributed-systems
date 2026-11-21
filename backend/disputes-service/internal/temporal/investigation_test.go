package temporal

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"net/url"
	"os"
	"strings"
	"testing"
	"time"

	disputesv1 "github.com/dipjyotimetia/distributed-systems/backend/disputes-service/internal/generated/disputes/v1"
	"github.com/dipjyotimetia/distributed-systems/backend/disputes-service/internal/postgres"
	enumspb "go.temporal.io/api/enums/v1"
	"go.temporal.io/api/serviceerror"
	"go.temporal.io/sdk/activity"
	"go.temporal.io/sdk/client"
	"go.temporal.io/sdk/testsuite"

	_ "github.com/jackc/pgx/v5/stdlib"
)

func TestInvestigationSignalAndEvidenceDeadline(t *testing.T) {
	var suite testsuite.WorkflowTestSuite
	env := suite.NewTestWorkflowEnvironment()
	start := time.Now().UTC().Truncate(time.Millisecond)
	env.SetStartTime(start)
	deadline := start.Add(7 * 24 * time.Hour)
	calls := 0
	env.RegisterActivityWithOptions(func(_ context.Context, rev *disputesv1.CaseRevision) (*disputesv1.InvestigationState, error) {
		calls++
		if rev.GetCaseId() != 42 {
			t.Errorf("wrong case %d", rev.GetCaseId())
		}
		switch calls {
		case 1:
			return &disputesv1.InvestigationState{NextDeadlineEpochMillis: start.Add(24 * time.Hour).UnixMilli()}, nil
		case 2:
			if rev.GetRevision() != 2 {
				t.Errorf("missing signal revision: %d", rev.GetRevision())
			}
			return &disputesv1.InvestigationState{NextDeadlineEpochMillis: deadline.UnixMilli()}, nil
		default:
			return &disputesv1.InvestigationState{Resolved: true}, nil
		}
	}, activity.RegisterOptions{Name: advanceActivity})
	env.RegisterDelayedCallback(func() {
		env.SignalWorkflow(changedSignal, &disputesv1.CaseRevision{CaseId: 42, Revision: 2})
	}, time.Hour)
	env.ExecuteWorkflow(investigateDispute, &disputesv1.CaseRevision{CaseId: 42})
	if err := env.GetWorkflowError(); err != nil || calls != 3 || env.Now().Before(deadline) {
		t.Fatalf("workflow ended at %s after %d activities: %v", env.Now(), calls, err)
	}
}

func TestMaybeStartAiResearch(t *testing.T) {
	dsn := os.Getenv("DISPUTES_TEST_DATABASE_URL")
	if dsn == "" {
		t.Skip("DISPUTES_TEST_DATABASE_URL is required for PostgreSQL tests")
	}
	db, err := sql.Open("pgx", dsn)
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	schema, err := os.ReadFile("../../../../kubernetes/overlays/full/schema.sql")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := db.Exec("CREATE SCHEMA IF NOT EXISTS disputes;" + string(schema)); err != nil {
		t.Fatal(err)
	}
	store := postgres.New(db)
	ctx := context.Background()
	c, created, err := store.Create(ctx, 7, time.Now().UnixNano(), "AI research case", time.Hour)
	if err != nil || !created {
		t.Fatalf("create case: %+v %v", c, err)
	}
	t.Cleanup(func() {
		_, _ = db.ExecContext(ctx, "DELETE FROM disputes.signal_outbox WHERE case_id=$1", c.ID)
		_, _ = db.ExecContext(ctx, "DELETE FROM disputes.workflow_outbox WHERE case_id=$1", c.ID)
		_, _ = db.ExecContext(ctx, "DELETE FROM disputes.cases WHERE id=$1", c.ID)
	})
	if err := store.Escalate(ctx, c.ID); err != nil {
		t.Fatal(err)
	}

	starts := 0
	starter := workflowStartFunc(func(_ context.Context, opts client.StartWorkflowOptions, workflow interface{}, args ...interface{}) (client.WorkflowRun, error) {
		starts++
		if opts.ID != fmt.Sprintf("dispute-ai-%d", c.ID) || opts.TaskQueue != aiBankingQueue {
			t.Fatalf("unexpected AI workflow start: %#v %#v", opts, workflow)
		}
		if opts.WorkflowIDReusePolicy != enumspb.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE_FAILED_ONLY {
			t.Fatalf("expected failed-only reuse policy, got %v", opts.WorkflowIDReusePolicy)
		}
		if len(args) != 1 {
			t.Fatalf("expected research input arg, got %#v", args)
		}
		input, ok := args[0].(disputeResearchInput)
		if !ok || input.CaseID != c.ID || input.Locale != "en-US" {
			t.Fatalf("unexpected research input: %#v", args[0])
		}
		return nil, &serviceerror.WorkflowExecutionAlreadyStarted{}
	})
	if err := maybeStartAiResearch(ctx, store, starter, c.ID); err != nil {
		t.Fatal(err)
	}
	if starts != 1 {
		t.Fatalf("expected one start attempt, got %d", starts)
	}

	draft, _ := json.Marshal(map[string]any{"summary": "fixture"})
	if _, err := store.SaveAiDraft(ctx, c.ID, draft); err != nil {
		t.Fatal(err)
	}
	if err := maybeStartAiResearch(ctx, store, starter, c.ID); err != nil {
		t.Fatal(err)
	}
	if starts != 1 {
		t.Fatalf("AI research restarted after draft saved: %d", starts)
	}

	state, err := advanceWithAI(ctx, store, starter, &disputesv1.CaseRevision{CaseId: c.ID})
	if err != nil || state.GetResolved() {
		t.Fatalf("advanceWithAI: %+v %v", state, err)
	}
	internal, err := store.ByIDInternal(ctx, c.ID)
	if err != nil || internal.AiDraft == nil {
		t.Fatalf("ByIDInternal missing draft: %+v %v", internal, err)
	}
}

func TestDispatchSignals(t *testing.T) {
	dsn := os.Getenv("DISPUTES_TEST_DATABASE_URL")
	if dsn == "" {
		t.Skip("DISPUTES_TEST_DATABASE_URL is required for signal dispatch tests")
	}
	base, err := sql.Open("pgx", dsn)
	if err != nil {
		t.Fatal(err)
	}
	defer base.Close()
	if _, err := base.Exec("CREATE DATABASE disputes_signal_test"); err != nil && !strings.Contains(err.Error(), "already exists") {
		t.Fatal(err)
	}
	isolated, err := url.Parse(dsn)
	if err != nil {
		t.Fatal(err)
	}
	isolated.Path = "/disputes_signal_test"
	db, err := sql.Open("pgx", isolated.String())
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	schema, err := os.ReadFile("../../../../kubernetes/overlays/full/schema.sql")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := db.Exec("CREATE SCHEMA IF NOT EXISTS disputes;" + string(schema)); err != nil {
		t.Fatal(err)
	}
	closed, err := sql.Open("pgx", isolated.String())
	if err != nil {
		t.Fatal(err)
	}
	closed.Close()
	if err := dispatchSignals(context.Background(), closed, &scriptedSignals{}); err == nil {
		t.Fatal("closed database accepted")
	}

	ctx := context.Background()
	var id int64
	if err := db.QueryRowContext(ctx, "INSERT INTO disputes.cases (transfer_id, customer_id, reason) VALUES ($1, 7, 'signal') RETURNING id", time.Now().UnixNano()).Scan(&id); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		_, _ = db.ExecContext(context.Background(), "DELETE FROM disputes.workflow_outbox WHERE case_id=$1", id)
		_, _ = db.ExecContext(context.Background(), "DELETE FROM disputes.signal_outbox WHERE case_id=$1", id)
		_, _ = db.ExecContext(context.Background(), "DELETE FROM disputes.cases WHERE id=$1", id)
	})
	if _, err := db.ExecContext(ctx, "INSERT INTO disputes.signal_outbox (case_id, revision) VALUES ($1, 2)", id); err != nil {
		t.Fatal(err)
	}
	signals := &scriptedSignals{err: errors.New("temporal down")}
	if err := dispatchSignals(ctx, db, signals); err == nil || signals.calls != 1 {
		t.Fatalf("signal error calls=%d err=%v", signals.calls, err)
	}
	signals.err = &serviceerror.NotFound{}
	if err := dispatchSignals(ctx, db, signals); err != nil || signals.calls != 2 {
		t.Fatalf("closed workflow calls=%d err=%v", signals.calls, err)
	}
	var pending int
	if err := db.QueryRowContext(ctx, "SELECT count(*) FROM disputes.signal_outbox WHERE case_id=$1", id).Scan(&pending); err != nil || pending != 0 {
		t.Fatalf("pending signals %d, %v", pending, err)
	}
	if _, err := db.ExecContext(ctx, "INSERT INTO disputes.signal_outbox (case_id, revision) VALUES ($1, 3)", id); err != nil {
		t.Fatal(err)
	}
	if _, err := db.ExecContext(ctx, "INSERT INTO disputes.workflow_outbox (case_id, deadline) VALUES ($1, now())", id); err != nil {
		t.Fatal(err)
	}
	if err := dispatchSignals(ctx, db, signals); err != nil || signals.calls != 2 {
		t.Fatalf("queued workflow was signalled calls=%d err=%v", signals.calls, err)
	}
}

type scriptedSignals struct {
	err   error
	calls int
}

func (s *scriptedSignals) SignalWorkflow(context.Context, string, string, string, interface{}) error {
	s.calls++
	return s.err
}

type workflowStartFunc func(context.Context, client.StartWorkflowOptions, interface{}, ...interface{}) (client.WorkflowRun, error)

func (f workflowStartFunc) ExecuteWorkflow(ctx context.Context, options client.StartWorkflowOptions, workflow interface{}, args ...interface{}) (client.WorkflowRun, error) {
	return f(ctx, options, workflow, args...)
}
