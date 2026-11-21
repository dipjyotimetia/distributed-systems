package main

import (
	"github.com/dipjyotimetia/distributed-systems/backend/disputes-service/internal/httpapi"
	"github.com/dipjyotimetia/distributed-systems/backend/disputes-service/internal/postgres"
	"github.com/dipjyotimetia/distributed-systems/backend/disputes-service/internal/temporal"
	researchv1 "github.com/dipjyotimetia/distributed-systems/backend/disputes-service/gen/disputes/research/v1"
	transactionsv1 "github.com/dipjyotimetia/distributed-systems/backend/disputes-service/gen/transactions/v1"

	"context"
	"database/sql"
	"errors"
	"fmt"
	"log/slog"
	"net"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials/insecure"

	_ "github.com/jackc/pgx/v5/stdlib"
	"go.opentelemetry.io/contrib/instrumentation/net/http/otelhttp"
	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/exporters/otlp/otlptrace/otlptracegrpc"
	"go.opentelemetry.io/otel/propagation"
	"go.opentelemetry.io/otel/sdk/resource"
	sdktrace "go.opentelemetry.io/otel/sdk/trace"
)

func main() {
	if len(os.Args) == 2 && os.Args[1] == "healthcheck" {
		probe := &http.Client{Timeout: 2 * time.Second}
		res, err := probe.Get("http://localhost:8080/api/v1/health")
		if err != nil {
			os.Exit(1)
		}
		res.Body.Close()
		if res.StatusCode != 200 {
			os.Exit(1)
		}
		return
	}
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	if err := run(ctx, os.Getenv("DISPUTES_MODE")); err != nil {
		slog.Error("Disputes process stopped", "error", err)
		os.Exit(1)
	}
}

func run(ctx context.Context, mode string) error {
	dsn := os.Getenv("DATABASE_URL")
	if dsn == "" {
		return errors.New("DATABASE_URL is required")
	}
	db, err := sql.Open("pgx", dsn)
	if err != nil {
		return errors.New("Invalid database configuration")
	}
	defer db.Close()
	startup, cancel := context.WithTimeout(ctx, 15*time.Second)
	defer cancel()

	if os.Getenv("OTEL_SDK_DISABLED") != "true" && os.Getenv("OTEL_EXPORTER_OTLP_ENDPOINT") != "" {
		exporter, err := otlptracegrpc.New(startup)
		if err != nil {
			return err
		}
		provider := sdktrace.NewTracerProvider(sdktrace.WithBatcher(exporter), sdktrace.WithResource(resource.Default()))
		otel.SetTracerProvider(provider)
		defer func() {
			shutdown, cancel := context.WithTimeout(context.Background(), 5*time.Second)
			defer cancel()
			_ = provider.Shutdown(shutdown)
		}()
	}
	otel.SetTextMapPropagator(propagation.TraceContext{})

	s := postgres.New(db)
	switch mode {
	case "worker":
		// Compose starts the worker after the dispute schema and API are ready.
		return temporal.Run(ctx, db, s, serve)
	case "", "api":
		secret := os.Getenv("JWT_SECRET")
		transactionsAddress := os.Getenv("TRANSACTIONS_GRPC_ADDRESS")
		if len(secret) < 32 || transactionsAddress == "" {
			return errors.New("JWT_SECRET of at least 32 bytes and TRANSACTIONS_GRPC_ADDRESS are required")
		}
		operators, err := httpapi.OperatorIDs(os.Getenv("DISPUTES_OPERATOR_IDS"))
		if err != nil {
			return err
		}
		delay, err := escalationDelay(os.Getenv("DISPUTES_TEST_ESCALATION_DELAY"))
		if err != nil {
			return err
		}
		if _, err := db.ExecContext(startup, "SELECT 1 FROM disputes.cases, disputes.workflow_outbox, disputes.signal_outbox LIMIT 0"); err != nil {
			return fmt.Errorf("check dispute schema: %w", err)
		}
		conn, err := grpc.NewClient(transactionsAddress, grpc.WithTransportCredentials(insecure.NewCredentials()))
		if err != nil {
			return err
		}
		defer conn.Close()
		a := httpapi.New(db, s, []byte(secret), operators, httpapi.Query{Client: transactionsv1.NewTransactionQueryClient(conn)}, delay)
		if err := serveResearch(ctx, a); err != nil {
			return err
		}
		return serve(ctx, a.Routes())
	default:
		return errors.New("invalid DISPUTES_MODE")
	}
}

func escalationDelay(value string) (time.Duration, error) {
	if value == "" {
		return 24 * time.Hour, nil
	}
	delay, err := time.ParseDuration(value)
	if err != nil || delay <= 0 {
		return 0, errors.New("DISPUTES_TEST_ESCALATION_DELAY must be a positive duration")
	}
	return delay, nil
}

func serveResearch(ctx context.Context, api httpapi.API) error {
	port := os.Getenv("DISPUTES_GRPC_PORT")
	if port == "" {
		port = "9096"
	}
	listener, err := net.Listen("tcp", ":"+port)
	if err != nil {
		return err
	}
	server := grpc.NewServer()
	researchv1.RegisterDisputeResearchServer(server, httpapi.ResearchServer{API: api})
	go func() {
		<-ctx.Done()
		server.GracefulStop()
	}()
	go func() {
		if err := server.Serve(listener); err != nil {
			slog.Error("Dispute research gRPC stopped", "error", err)
		}
	}()
	return nil
}

func serve(ctx context.Context, mux *http.ServeMux) error {
	handler := otelhttp.NewHandler(mux, "disputes.http", otelhttp.WithFilter(func(r *http.Request) bool {
		return r.URL.Path != "/api/v1/health"
	}))
	server := &http.Server{
		Addr: ":8080", Handler: handler, ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout: 10 * time.Second, WriteTimeout: 15 * time.Second, IdleTimeout: time.Minute,
	}
	done := make(chan error, 1)
	go func() { done <- server.ListenAndServe() }()
	select {
	case err := <-done:
		return err
	case <-ctx.Done():
		shutdown, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		return server.Shutdown(shutdown)
	}
}
