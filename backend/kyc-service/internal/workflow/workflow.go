package workflow

import (
	"context"
	"time"

	"github.com/dipjyotimetia/distributed-systems/backend/kyc-service/internal/screen"
	"github.com/dipjyotimetia/distributed-systems/backend/kyc-service/internal/store"
	"go.temporal.io/sdk/workflow"
)

const ReviewWorkflow = "reviewKycV1"
const DecisionSignal = "kycDecisionV1"

type Input struct{ CaseID string }

type Activities struct{ Store *store.Store }

func Review(ctx workflow.Context, in Input) error {
	ctx = workflow.WithActivityOptions(ctx, workflow.ActivityOptions{StartToCloseTimeout: 10 * time.Second})
	var reference string
	if err := workflow.ExecuteActivity(ctx, "LoadReference", in.CaseID).Get(ctx, &reference); err != nil {
		return err
	}
	var decided *bool
	if err := workflow.ExecuteActivity(ctx, "PendingDecision", in.CaseID).Get(ctx, &decided); err != nil {
		return err
	}
	status := screen.Outcome(reference)
	if decided != nil {
		status = statusFrom(*decided)
	} else if status == "REVIEW" {
		var approve bool
		workflow.GetSignalChannel(ctx, DecisionSignal).Receive(ctx, &approve)
		status = statusFrom(approve)
	}
	return workflow.ExecuteActivity(ctx, "Complete", in.CaseID, status).Get(ctx, nil)
}

func statusFrom(approve bool) string {
	if approve {
		return "APPROVED"
	}
	return "REJECTED"
}

func (a *Activities) LoadReference(ctx context.Context, id string) (string, error) {
	return a.Store.Reference(ctx, id)
}

func (a *Activities) PendingDecision(ctx context.Context, id string) (*bool, error) {
	return a.Store.Decision(ctx, id)
}

func (a *Activities) Complete(ctx context.Context, id, status string) error {
	return a.Store.Complete(ctx, id, status)
}
