package integration

import (
	"time"

	"go.temporal.io/sdk/temporal"
	"go.temporal.io/sdk/workflow"
)

const (
	Checkpoint = "transfer-committed"
	Resume     = "resume"
)

type Input struct{ User string }

type Transfer struct {
	From, To, ID, UserID int64
	Evidence             []BoundaryEvidence
}

func mutationOptions() workflow.ActivityOptions {
	return workflow.ActivityOptions{
		StartToCloseTimeout: 45 * time.Second,
		RetryPolicy:         &temporal.RetryPolicy{MaximumAttempts: 1},
	}
}

func Journey(ctx workflow.Context, input Input) (Transfer, error) {
	var a *Activities
	var transfer Transfer
	writes := workflow.WithActivityOptions(ctx, mutationOptions())
	if err := workflow.ExecuteActivity(writes, a.Register, input).Get(ctx, &transfer.UserID); err != nil {
		return transfer, err
	}
	if err := workflow.ExecuteActivity(writes, a.CreateAccount, input, "CHECKING", 100).Get(ctx, &transfer.From); err != nil {
		return transfer, err
	}
	if err := workflow.ExecuteActivity(writes, a.CreateAccount, input, "SAVINGS", 20).Get(ctx, &transfer.To); err != nil {
		return transfer, err
	}
	if err := workflow.ExecuteActivity(writes, a.SubmitTransfer, input, transfer).Get(ctx, &transfer.ID); err != nil {
		return transfer, err
	}
	ready := true
	if err := workflow.SetQueryHandler(ctx, Checkpoint, func() (bool, error) { return ready, nil }); err != nil {
		return transfer, err
	}
	workflow.GetSignalChannel(ctx, Resume).Receive(ctx, nil)
	reads := workflow.WithActivityOptions(ctx, evidenceOptions())
	if err := workflow.ExecuteActivity(reads, a.Verify, input, transfer).Get(ctx, nil); err != nil {
		return transfer, err
	}
	if err := workflow.ExecuteActivity(writes, a.Reject, input, transfer).Get(ctx, nil); err != nil {
		return transfer, err
	}
	if err := workflow.ExecuteActivity(reads, a.Verify, input, transfer).Get(ctx, nil); err != nil {
		return transfer, err
	}
	if err := workflow.ExecuteActivity(reads, a.VerifyChecks, transfer).Get(ctx, nil); err != nil {
		return transfer, err
	}
	if err := workflow.ExecuteActivity(writes, a.AssertFraudRejections, input, transfer).Get(ctx, nil); err != nil {
		return transfer, err
	}
	if err := workflow.ExecuteActivity(reads, a.Verify, input, transfer).Get(ctx, nil); err != nil {
		return transfer, err
	}
	var fraud BoundaryEvidence
	if err := workflow.ExecuteActivity(reads, a.VerifyFraudRejections, transfer).Get(ctx, &fraud); err != nil {
		return transfer, err
	}
	boundaries := []BoundaryEvidence{
		{"Frontend ledger", "one USD 25 transfer with balances USD 75 and USD 45", "owned balances and ledger matched before and after rejected transfers"},
		fraud,
	}
	for _, probe := range []any{a.VerifyKafka, a.VerifyMongo, a.VerifyDeliveries} {
		var evidence BoundaryEvidence
		if err := workflow.ExecuteActivity(reads, probe, transfer).Get(ctx, &evidence); err != nil {
			return transfer, err
		}
		boundaries = append(boundaries, evidence)
	}
	transfer.Evidence = boundaries
	return transfer, nil
}

func evidenceOptions() workflow.ActivityOptions {
	return workflow.ActivityOptions{
		StartToCloseTimeout:    25 * time.Second,
		ScheduleToCloseTimeout: 60 * time.Second,
		RetryPolicy:            &temporal.RetryPolicy{MaximumAttempts: 3},
	}
}
