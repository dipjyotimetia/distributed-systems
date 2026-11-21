package temporal

import (
	"context"
	"database/sql"
	"encoding/json"
	"fmt"
	"os"
	"testing"
	"time"

	disputesv1 "github.com/dipjyotimetia/distributed-systems/backend/disputes-service/internal/generated/disputes/v1"
	"github.com/dipjyotimetia/distributed-systems/backend/disputes-service/internal/postgres"
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

type workflowStartFunc func(context.Context, client.StartWorkflowOptions, interface{}, ...interface{}) (client.WorkflowRun, error)

func (f workflowStartFunc) ExecuteWorkflow(ctx context.Context, options client.StartWorkflowOptions, workflow interface{}, args ...interface{}) (client.WorkflowRun, error) {
	return f(ctx, options, workflow, args...)
}
