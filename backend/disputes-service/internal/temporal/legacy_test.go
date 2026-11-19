package temporal

import (
	"context"
	"go.temporal.io/sdk/activity"
	"go.temporal.io/sdk/testsuite"
	"go.temporal.io/sdk/worker"
	"go.temporal.io/sdk/workflow"
	"testing"
	"time"
)

func TestEscalationTimer(t *testing.T) {
	for _, delay := range []time.Duration{24 * time.Hour, -time.Hour} {
		var suite testsuite.WorkflowTestSuite
		env := suite.NewTestWorkflowEnvironment()
		start := time.Now().UTC()
		env.SetStartTime(start)
		deadline := start.Add(delay)
		calls := 0
		env.RegisterActivityWithOptions(func(_ context.Context, id int64) error {
			calls++
			if id != 42 {
				t.Errorf("activity got case %d", id)
			}
			return nil
		}, activity.RegisterOptions{Name: "escalate"})
		env.ExecuteWorkflow(escalateDispute, Escalation{42, deadline})
		if err := env.GetWorkflowError(); err != nil || calls != 1 || env.Now().Before(deadline) {
			t.Fatalf("escalation deadline %s, completed %s, calls %d, error %v", deadline, env.Now(), calls, err)
		}
	}
}

// The fixture was captured from the pre-rollout worker with a synthetic case.
func TestReplayLegacyHistory(t *testing.T) {
	replayer := worker.NewWorkflowReplayer()
	replayer.RegisterWorkflowWithOptions(escalateDispute, workflow.RegisterOptions{Name: "escalateDispute"})
	replayer.RegisterWorkflowWithOptions(investigateDispute, workflow.RegisterOptions{Name: investigationWorkflow})
	if err := replayer.ReplayWorkflowHistoryFromJSONFile(nil, "testdata/legacy-escalation.json"); err != nil {
		t.Fatal(err)
	}
}
