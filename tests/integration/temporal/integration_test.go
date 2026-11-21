package integration

import (
	"context"
	"crypto/rand"
	"encoding/json"
	"fmt"
	"os"
	"os/exec"
	"os/signal"
	"strconv"
	"strings"
	"testing"
	"time"

	enumspb "go.temporal.io/api/enums/v1"
	historypb "go.temporal.io/api/history/v1"
	"go.temporal.io/sdk/client"
	"go.temporal.io/sdk/testsuite"
	"go.temporal.io/sdk/worker"
)

func TestWorkerProcess(t *testing.T) {
	if os.Getenv("MICROSVC_WORKER") != "1" {
		t.Skip("worker subprocess")
	}
	c, err := client.Dial(client.Options{HostPort: os.Getenv("MICROSVC_TEMPORAL")})
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	w := worker.New(c, os.Getenv("MICROSVC_QUEUE"), worker.Options{Identity: os.Getenv("MICROSVC_WORKER_ID")})
	w.RegisterWorkflow(Journey)
	w.RegisterWorkflow(IdentityJourney)
	w.RegisterWorkflow(RecoveryJourney)
	w.RegisterWorkflow(AIJourney)
	w.RegisterWorkflow(LedgerJourney)
	w.RegisterWorkflow(StatementJourney)
	w.RegisterActivity(&Activities{
		Frontend: os.Getenv("MICROSVC_FRONTEND"), Password: os.Getenv("MICROSVC_TEST_PASSWORD"),
		Deliveries:      os.Getenv("MICROSVC_DELIVERIES"),
		BrokerContainer: os.Getenv("MICROSVC_BROKER_CONTAINER"), MongoContainer: os.Getenv("MICROSVC_MONGO_CONTAINER"),
		ConsumerContainer: os.Getenv("MICROSVC_CONSUMER_CONTAINER"), PostgresContainer: os.Getenv("MICROSVC_POSTGRES_CONTAINER"), FailedProvider: os.Getenv("MICROSVC_FAILED_PROVIDER"),
	})
	if err := w.Run(worker.InterruptCh()); err != nil {
		t.Fatal(err)
	}
}

func TestBankingIntegration(t *testing.T) {
	scenario := os.Getenv("MICROSVC_SCENARIO")
	switch scenario {
	case "", "transfer-worker-replacement":
		t.Run("transfer-worker-replacement", func(t *testing.T) { testJourney(t, "transfer-worker-replacement", Journey, true) })
	case "identity":
		t.Run("identity", func(t *testing.T) { testJourney(t, "identity", IdentityJourney, false) })
	case "consumer-recovery":
		t.Run("consumer-recovery", func(t *testing.T) { testJourney(t, "consumer-recovery", RecoveryJourney, false) })
	case "ai":
		t.Run("ai", func(t *testing.T) { testJourney(t, "ai", AIJourney, false) })
	case "ledger":
		t.Run("ledger", func(t *testing.T) { testJourney(t, "ledger", LedgerJourney, false) })
	case "statement-export":
		t.Run("statement-export", func(t *testing.T) { testJourney(t, "statement-export", StatementJourney, false) })
	default:
		t.Fatalf("unknown integration scenario %q", scenario)
	}
}

func testJourney(t *testing.T, scenario string, definition any, replaceWorker bool) {
	frontend := os.Getenv("MICROSVC_FRONTEND")
	if frontend == "" {
		t.Skip("run make test-integration-temporal")
	}
	uiPort := os.Getenv("TEMPORAL_TEST_UI_PORT")
	var inspectFor time.Duration
	if uiPort != "" {
		port, err := strconv.Atoi(uiPort)
		if err != nil || port < 1 || port > 65535 {
			t.Fatal("TEMPORAL_TEST_UI_PORT must be between 1 and 65535")
		}
		hold := os.Getenv("TEMPORAL_TEST_UI_HOLD")
		if hold == "" {
			hold = "2m"
		}
		inspectFor, err = time.ParseDuration(hold)
		if err != nil || inspectFor < 0 || inspectFor > 10*time.Minute {
			t.Fatal("TEMPORAL_TEST_UI_HOLD must be a duration between 0s and 10m")
		}
	}
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Minute)
	defer cancel()
	started := time.Now()
	id := "bank" + strings.ToLower(rand.Text()[:16])
	report := map[string]any{"scenario": scenario, "workflow_id": id, "workflow_passed": false, "history_replay_passed": false}
	t.Cleanup(func() {
		report["duration_ms"] = time.Since(started).Milliseconds()
		data, err := json.MarshalIndent(report, "", "  ")
		if err == nil {
			err = os.WriteFile(os.Getenv("MICROSVC_REPORT"), data, 0600)
		}
		if err != nil {
			t.Error(err)
		}
	})
	report["workflow_stage"] = "readiness"
	waitFor(t, ctx, func(ctx context.Context) bool {
		if scenario == "ai" {
			return call(ctx, frontend, "/api/v1/healthz", "", nil, 200, nil) == nil &&
				call(ctx, frontend, "/api/ai/chat", "", nil, 200, &[]struct{ Provider string }{}) == nil
		}
		gateway := os.Getenv("MICROSVC_GATEWAY")
		for _, path := range []string{"/api/v1/user-service/health", "/api/v1/accounts-service/health", "/api/v1/transactions-service/health"} {
			if call(ctx, gateway, path, "", nil, 200, nil) != nil {
				return false
			}
		}

		return call(ctx, frontend, "/api/v1/healthz", "", nil, 200, nil) == nil &&
			call(ctx, os.Getenv("MICROSVC_NOTIFICATIONS"), "/api/v1/health", "", nil, 200, nil) == nil
	})
	report["workflow_stage"] = "temporal_startup"
	dev, err := testsuite.StartDevServer(ctx, testsuite.DevServerOptions{
		CachedDownload: testsuite.CachedDownload{Version: "v1.10.1"},
		DBFilename:     t.TempDir() + "/temporal.sqlite",
		EnableUI:       uiPort != "",
		UIPort:         uiPort,
	})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if err := dev.Stop(); err != nil {
			t.Error(err)
		}
	})
	var uiURL string
	if uiPort != "" {
		uiURL = "http://127.0.0.1:" + uiPort + "/namespaces/default/workflows"
		t.Logf("Temporal UI: %s", uiURL)
		report["temporal_ui_url"] = uiURL
		t.Cleanup(func() {
			if inspectFor == 0 {
				return
			}
			fmt.Printf("Inspect workflow %s at %s for %s (Ctrl+C to finish).\n", id, uiURL, inspectFor)
			signals, stop := signal.NotifyContext(context.Background(), os.Interrupt)
			defer stop()
			hold, cancel := context.WithTimeout(signals, inspectFor)
			defer cancel()
			<-hold.Done()
		})
	}
	c := dev.Client()
	password := "T3st!" + rand.Text()
	startWorker := func(identity string) func() {
		cmd := exec.CommandContext(ctx, os.Args[0], "-test.run=^TestWorkerProcess$")
		cmd.Env = append(os.Environ(), "MICROSVC_WORKER=1", "MICROSVC_WORKER_ID="+identity, "MICROSVC_TEMPORAL="+dev.FrontendHostPort(), "MICROSVC_QUEUE="+id, "MICROSVC_TEST_PASSWORD="+password)
		cmd.Stdout, cmd.Stderr = os.Stdout, os.Stderr
		if err := cmd.Start(); err != nil {
			t.Fatal(err)
		}
		return func() { _ = cmd.Process.Kill(); _ = cmd.Wait() }
	}
	stop := startWorker("initial-worker")
	defer func() { stop() }()
	report["workflow_stage"] = "initial_worker"
	run, err := c.ExecuteWorkflow(ctx, client.StartWorkflowOptions{ID: id, TaskQueue: id, WorkflowExecutionTimeout: 2 * time.Minute}, definition, Input{User: id})
	if err != nil {
		t.Fatal(err)
	}
	runID := run.GetRunID()
	report["run_id"] = runID
	if uiURL != "" {
		uiURL += "/" + id + "/" + runID + "/history"
		report["temporal_ui_url"] = uiURL
		t.Logf("Temporal workflow history: %s", uiURL)
	}
	expectedWorker := "initial-worker"
	if replaceWorker {
		waitFor(t, ctx, func(ctx context.Context) bool {
			state, err := c.QueryWorkflow(ctx, id, runID, Checkpoint)
			if err != nil {
				return false
			}
			var ready bool
			return state.Get(&ready) == nil && ready
		})
		report["commit_ms"] = time.Since(started).Milliseconds()
		stop()
		report["workflow_stage"] = "replacement_worker"
		expectedWorker = "replacement-worker"
		stop = startWorker(expectedWorker)
		if err := c.SignalWorkflow(ctx, id, runID, Resume, nil); err != nil {
			t.Fatal(err)
		}
	}
	if replaceWorker {
		var transfer Transfer
		if err := run.Get(ctx, &transfer); err != nil {
			t.Fatal(err)
		}
		report["transfer_id"] = transfer.ID
		report["boundaries"] = transfer.Evidence
	} else {
		var evidence []BoundaryEvidence
		if err := run.Get(ctx, &evidence); err != nil {
			t.Fatal(err)
		}
		report["boundaries"] = evidence
	}
	report["workflow_passed"] = true
	report["workflow_stage"] = "history_replay"
	events := &historypb.History{}
	history := c.GetWorkflowHistory(ctx, id, runID, false, enumspb.HISTORY_EVENT_FILTER_TYPE_ALL_EVENT)
	for history.HasNext() {
		event, err := history.Next()
		if err != nil {
			t.Fatal(err)
		}
		events.Events = append(events.Events, event)
	}
	if len(events.Events) == 0 {
		t.Fatal("workflow history is empty")
	}
	completion := events.Events[len(events.Events)-1].GetWorkflowExecutionCompletedEventAttributes()
	if completion == nil {
		t.Fatal("workflow history has no completion event")
	}
	for _, event := range events.Events {
		if event.GetEventId() == completion.GetWorkflowTaskCompletedEventId() {
			report["completion_worker"] = event.GetWorkflowTaskCompletedEventAttributes().GetIdentity()
			break
		}
	}
	if report["completion_worker"] != expectedWorker {
		t.Fatal("expected worker did not complete the original run")
	}
	replayer := worker.NewWorkflowReplayer()
	replayer.RegisterWorkflow(definition)
	if err := replayer.ReplayWorkflowHistory(nil, events); err != nil {
		t.Fatalf("workflow history replay failed: %v", err)
	}
	report["history_replay_passed"] = true
	report["workflow_stage"] = "complete"
	report["completed_ms"] = time.Since(started).Milliseconds()
}

func waitFor(t *testing.T, ctx context.Context, probe func(context.Context) bool) {
	t.Helper()
	ctx, cancel := context.WithTimeout(ctx, 60*time.Second)
	defer cancel()
	ticker := time.NewTicker(time.Second)
	defer ticker.Stop()
	for {
		attempt, stop := context.WithTimeout(ctx, 3*time.Second)
		ok := probe(attempt)
		stop()
		if ok {
			return
		}
		select {
		case <-ticker.C:
		case <-ctx.Done():
			t.Fatal("timed out waiting for readiness or workflow checkpoint")
		}
	}
}
