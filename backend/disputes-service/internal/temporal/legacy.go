package temporal

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"time"

	disputesv1 "github.com/speedscale/microsvc/disputes-service/internal/generated/disputes/v1"
	"github.com/speedscale/microsvc/disputes-service/internal/postgres"
	"go.opentelemetry.io/otel"
	enumspb "go.temporal.io/api/enums/v1"
	"go.temporal.io/api/serviceerror"
	"go.temporal.io/sdk/activity"
	"go.temporal.io/sdk/client"
	"go.temporal.io/sdk/worker"
	"go.temporal.io/sdk/workflow"
)

const TaskQueue = "disputes-escalation"

type Escalation struct {
	CaseID   int64
	Deadline time.Time
}

func escalateDispute(ctx workflow.Context, input Escalation) error {
	if delay := input.Deadline.Sub(workflow.Now(ctx)); delay > 0 {
		if err := workflow.Sleep(ctx, delay); err != nil {
			return err
		}
	}
	ctx = workflow.WithActivityOptions(ctx, workflow.ActivityOptions{StartToCloseTimeout: 10 * time.Second})
	return workflow.ExecuteActivity(ctx, "escalate", input.CaseID).Get(ctx, nil)
}

type WorkflowStarter interface {
	ExecuteWorkflow(context.Context, client.StartWorkflowOptions, interface{}, ...interface{}) (client.WorkflowRun, error)
}

func startDispute(ctx context.Context, temporal WorkflowStarter, input Escalation, modern bool) error {
	name := "escalateDispute"
	var payload any = input
	if modern {
		name = investigationWorkflow
		payload = &disputesv1.CaseRevision{CaseId: input.CaseID}
	}
	_, err := temporal.ExecuteWorkflow(ctx, client.StartWorkflowOptions{
		ID:                                       fmt.Sprintf("dispute-%d", input.CaseID),
		TaskQueue:                                TaskQueue,
		WorkflowIDReusePolicy:                    enumspb.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE,
		WorkflowExecutionErrorWhenAlreadyStarted: true,
	}, name, payload)
	var alreadyStarted *serviceerror.WorkflowExecutionAlreadyStarted
	if errors.As(err, &alreadyStarted) {
		return nil
	}
	return err
}

func Dispatch(ctx context.Context, db *sql.DB, temporal WorkflowStarter) error {
	rows, err := db.QueryContext(ctx, `SELECT case_id, deadline, escalation_deadline IS NOT NULL
 FROM disputes.workflow_outbox JOIN disputes.cases ON id=case_id ORDER BY case_id LIMIT 100`)
	if err != nil {
		return err
	}
	defer rows.Close()
	type pending struct {
		Escalation
		modern bool
	}
	var batch []pending
	for rows.Next() {
		var input pending
		if err := rows.Scan(&input.CaseID, &input.Deadline, &input.modern); err != nil {
			return err
		}
		batch = append(batch, input)
	}
	if err := rows.Err(); err != nil {
		return err
	}
	for _, input := range batch {
		startCtx, cancel := context.WithTimeout(ctx, 5*time.Second)
		err := startDispute(startCtx, temporal, input.Escalation, input.modern)
		cancel()
		if err != nil {
			return err
		}
		if _, err := db.ExecContext(ctx, "DELETE FROM disputes.workflow_outbox WHERE case_id=$1", input.CaseID); err != nil {
			return err
		}
	}
	return nil
}
func Run(ctx context.Context, db *sql.DB, s *postgres.Store, serve func(context.Context, *http.ServeMux) error) error {
	temporal, err := client.NewLazyClient(client.Options{HostPort: os.Getenv("TEMPORAL_ADDRESS")})
	if err != nil {
		return err
	}
	defer temporal.Close()
	w := worker.New(temporal, TaskQueue, worker.Options{})
	w.RegisterWorkflowWithOptions(escalateDispute, workflow.RegisterOptions{Name: "escalateDispute"})
	w.RegisterWorkflowWithOptions(investigateDispute, workflow.RegisterOptions{Name: investigationWorkflow})
	w.RegisterActivityWithOptions(func(ctx context.Context, input *disputesv1.CaseRevision) (*disputesv1.InvestigationState, error) {
		return advance(ctx, s, input)
	}, activity.RegisterOptions{Name: advanceActivity})
	w.RegisterActivityWithOptions(func(ctx context.Context, id int64) error {
		ctx, span := otel.Tracer("disputes").Start(ctx, "disputes.escalate")
		defer span.End()
		err := s.Escalate(ctx, id)
		if err != nil {
			span.RecordError(err)
		}
		return err
	}, activity.RegisterOptions{Name: "escalate"})
	if err := w.Start(); err != nil {
		return err
	}
	defer w.Stop()
	health := http.NewServeMux()
	health.HandleFunc("GET /health", func(w http.ResponseWriter, r *http.Request) {
		probe, cancel := context.WithTimeout(r.Context(), time.Second)
		defer cancel()
		if err := db.PingContext(probe); err != nil {
			w.WriteHeader(http.StatusServiceUnavailable)
			return
		}
		if _, err := temporal.CheckHealth(probe, &client.CheckHealthRequest{}); err != nil {
			w.WriteHeader(http.StatusServiceUnavailable)
			return
		}
		w.WriteHeader(http.StatusOK)
	})
	healthCtx, stopHealth := context.WithCancel(ctx)
	defer stopHealth()
	healthDone := make(chan error, 1)
	go func() { healthDone <- serve(healthCtx, health) }()
	ticker := time.NewTicker(time.Second)
	defer ticker.Stop()
	for {
		if err := Dispatch(ctx, db, temporal); err != nil && ctx.Err() == nil {
			slog.Warn("Dispute workflow dispatch will retry", "error", err)
		}
		if err := dispatchSignals(ctx, db, temporal); err != nil && ctx.Err() == nil {
			slog.Warn("Dispute signal dispatch will retry", "error", err)
		}
		select {
		case <-ctx.Done():
			return <-healthDone
		case err := <-healthDone:
			return err
		case <-ticker.C:
		}
	}
}
