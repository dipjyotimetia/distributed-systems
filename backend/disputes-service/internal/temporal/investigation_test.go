package temporal

import (
	"context"
	"testing"
	"time"

	disputesv1 "github.com/dipjyotimetia/distributed-systems/backend/disputes-service/internal/generated/disputes/v1"
	"go.temporal.io/sdk/activity"
	"go.temporal.io/sdk/testsuite"
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
