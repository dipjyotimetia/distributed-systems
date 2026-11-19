package temporal

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"time"

	disputesv1 "github.com/speedscale/microsvc/disputes-service/internal/generated/disputes/v1"
	"github.com/speedscale/microsvc/disputes-service/internal/postgres"
	"go.opentelemetry.io/otel"
	"go.temporal.io/api/serviceerror"
	"go.temporal.io/sdk/workflow"
)

const investigationWorkflow = "investigateDisputeV1"
const advanceActivity = "advanceInvestigationV1"
const changedSignal = "caseChangedV1"

func investigateDispute(ctx workflow.Context, input *disputesv1.CaseRevision) error {
	ctx = workflow.WithActivityOptions(ctx, workflow.ActivityOptions{StartToCloseTimeout: 10 * time.Second})
	changed := workflow.GetSignalChannel(ctx, changedSignal)
	for {
		var state disputesv1.InvestigationState
		if err := workflow.ExecuteActivity(ctx, advanceActivity, input).Get(ctx, &state); err != nil {
			return err
		}
		if state.GetResolved() {
			return nil
		}
		selector := workflow.NewSelector(ctx)
		selector.AddReceive(changed, func(channel workflow.ReceiveChannel, _ bool) {
			var revision disputesv1.CaseRevision
			channel.Receive(ctx, &revision)
			if revision.GetRevision() > input.GetRevision() {
				input = &revision
			}
		})
		timerCtx, cancel := workflow.WithCancel(ctx)
		if deadline := state.GetNextDeadlineEpochMillis(); deadline != 0 {
			delay := time.UnixMilli(deadline).Sub(workflow.Now(ctx))
			selector.AddFuture(workflow.NewTimer(timerCtx, delay), func(workflow.Future) {})
		}
		selector.Select(ctx)
		cancel()
		if err := ctx.Err(); err != nil {
			return err
		}
	}
}

func advance(ctx context.Context, store *postgres.Store, input *disputesv1.CaseRevision) (*disputesv1.InvestigationState, error) {
	ctx, span := otel.Tracer("disputes").Start(ctx, "disputes.advanceInvestigation")
	defer span.End()
	resolved, deadline, err := store.Advance(ctx, input.GetCaseId())
	if err != nil {
		span.RecordError(err)
		return nil, err
	}
	state := &disputesv1.InvestigationState{Resolved: resolved}
	if deadline != nil {
		state.NextDeadlineEpochMillis = deadline.Add(time.Millisecond - time.Nanosecond).UnixMilli()
	}
	return state, nil
}

type signalClient interface {
	SignalWorkflow(context.Context, string, string, string, interface{}) error
}

func dispatchSignals(ctx context.Context, db *sql.DB, temporal signalClient) error {
	rows, err := db.QueryContext(ctx, `SELECT case_id, revision FROM disputes.signal_outbox s
		WHERE NOT EXISTS (SELECT FROM disputes.workflow_outbox w WHERE w.case_id=s.case_id)
		ORDER BY case_id LIMIT 100`)
	if err != nil {
		return err
	}
	batch := []*disputesv1.CaseRevision{}
	for rows.Next() {
		input := new(disputesv1.CaseRevision)
		if err := rows.Scan(&input.CaseId, &input.Revision); err != nil {
			rows.Close()
			return err
		}
		batch = append(batch, input)
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return err
	}
	for _, input := range batch {
		call, cancel := context.WithTimeout(ctx, 5*time.Second)
		err := temporal.SignalWorkflow(call, fmt.Sprintf("dispute-%d", input.GetCaseId()), "", changedSignal, input)
		cancel()
		var closed *serviceerror.NotFound
		if err != nil && !errors.As(err, &closed) {
			return err
		}
		if _, err := db.ExecContext(ctx, "DELETE FROM disputes.signal_outbox WHERE case_id=$1 AND revision=$2", input.GetCaseId(), input.GetRevision()); err != nil {
			return err
		}
	}
	return nil
}
