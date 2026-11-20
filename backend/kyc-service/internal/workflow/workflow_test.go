package workflow

import (
	"context"
	"testing"
	"time"

	"github.com/dipjyotimetia/distributed-systems/backend/kyc-service/internal/screen"
	"go.temporal.io/sdk/activity"
	"go.temporal.io/sdk/testsuite"
)

func TestReviewApprovesWithoutWaiting(t *testing.T) {
	completed := ""
	env := (&testsuite.WorkflowTestSuite{}).NewTestWorkflowEnvironment()
	env.RegisterActivityWithOptions(func(context.Context, string) (string, error) { return "passport", nil }, activity.RegisterOptions{Name: "LoadReference"})
	env.RegisterActivityWithOptions(func(context.Context, string) (*bool, error) { return nil, nil }, activity.RegisterOptions{Name: "PendingDecision"})
	env.RegisterActivityWithOptions(func(_ context.Context, _ string, status string) error {
		completed = status
		return nil
	}, activity.RegisterOptions{Name: "Complete"})
	env.ExecuteWorkflow(Review, Input{CaseID: "case"})
	if err := env.GetWorkflowError(); err != nil || completed != screen.Outcome("passport") {
		t.Fatal(err, completed)
	}
}

func TestReviewWaitsForTheOperator(t *testing.T) {
	completed := ""
	env := (&testsuite.WorkflowTestSuite{}).NewTestWorkflowEnvironment()
	env.RegisterActivityWithOptions(func(context.Context, string) (string, error) { return "REVIEW", nil }, activity.RegisterOptions{Name: "LoadReference"})
	env.RegisterActivityWithOptions(func(context.Context, string) (*bool, error) { return nil, nil }, activity.RegisterOptions{Name: "PendingDecision"})
	env.RegisterActivityWithOptions(func(_ context.Context, _ string, status string) error {
		completed = status
		return nil
	}, activity.RegisterOptions{Name: "Complete"})
	env.RegisterDelayedCallback(func() { env.SignalWorkflow(DecisionSignal, false) }, time.Second)
	env.ExecuteWorkflow(Review, Input{CaseID: "case"})
	if err := env.GetWorkflowError(); err != nil || completed != "REJECTED" {
		t.Fatal(err, completed)
	}
}
