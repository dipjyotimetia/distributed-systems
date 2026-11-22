package main

import (
	"context"
	"log"
	"net"
	"net/http"
	"os"

	cardsv1 "github.com/dipjyotimetia/distributed-systems/backend/cards-service/gen/cards/v1"
	ledgerv1 "github.com/dipjyotimetia/distributed-systems/backend/cards-service/gen/ledger/v1"
	"github.com/dipjyotimetia/distributed-systems/backend/cards-service/internal/ledger"
	"github.com/dipjyotimetia/distributed-systems/backend/cards-service/internal/mtls"
	otelsetup "github.com/dipjyotimetia/distributed-systems/backend/cards-service/internal/otel"
	"github.com/dipjyotimetia/distributed-systems/backend/cards-service/internal/service"
	"github.com/dipjyotimetia/distributed-systems/backend/cards-service/internal/store"
	fraudv1 "github.com/dipjyotimetia/distributed-systems/backend/fraud-service/gen/fraud/v1"
	kycv1 "github.com/dipjyotimetia/distributed-systems/backend/kyc-service/gen/kyc/v1"
	"github.com/prometheus/client_golang/prometheus/promhttp"
	"go.opentelemetry.io/contrib/instrumentation/google.golang.org/grpc/otelgrpc"
	"google.golang.org/grpc"
)

func main() {
	ctx := context.Background()
	shutdown, err := otelsetup.Init(ctx, "cards-service")
	if err != nil {
		log.Fatal(err)
	}
	defer func() { _ = shutdown(context.Background()) }()
	db, err := store.Open(env("DATABASE_URL", "postgres://postgres:password@postgres:5432/banking_app?sslmode=disable"))
	if err != nil {
		log.Fatal(err)
	}
	defer db.Close()
	if err := db.Migrate(ctx); err != nil {
		log.Fatal(err)
	}
	kycConn, err := dial(env("KYC_GRPC_ADDR", "kyc-service:8091"), "kyc-service")
	if err != nil {
		log.Fatal(err)
	}
	defer kycConn.Close()
	accountsConn, err := dial(env("ACCOUNTS_GRPC_ADDRESS", "accounts-service:9092"), "accounts-service")
	if err != nil {
		log.Fatal(err)
	}
	defer accountsConn.Close()
	fraudConn, err := dial(env("FRAUD_GRPC_ADDR", "fraud-service:8080"), "fraud-service")
	if err != nil {
		log.Fatal(err)
	}
	defer fraudConn.Close()
	option, err := mtls.ServerOption()
	if err != nil {
		log.Fatal(err)
	}
	options := []grpc.ServerOption{grpc.StatsHandler(otelgrpc.NewServerHandler())}
	if option != nil {
		options = append(options, option)
	}
	server := grpc.NewServer(options...)
	secret := env("JWT_SECRET", "")
	cardsv1.RegisterCardsServer(server, &service.Server{
		Repo: db, Secret: []byte(secret),
		Ledger: ledger.Client{Ledger: ledgerv1.NewAccountLedgerClient(accountsConn), Secret: env("MOVEMENT_SECRET", secret)},
		Fraud:  fraudv1.NewFraudCheckerClient(fraudConn),
		Kyc:    kycv1.NewKycClient(kycConn),
	})
	go func() {
		mux := http.NewServeMux()
		mux.HandleFunc("/api/v1/health", func(w http.ResponseWriter, _ *http.Request) { w.WriteHeader(http.StatusOK) })
		mux.Handle("/metrics", promhttp.Handler())
		log.Fatal(http.ListenAndServe(":"+env("METRICS_PORT", "9093"), mux))
	}()
	listener, err := net.Listen("tcp", ":"+env("GRPC_PORT", "8092"))
	if err != nil {
		log.Fatal(err)
	}
	log.Println("cards gRPC", listener.Addr())
	log.Fatal(server.Serve(listener))
}

func dial(address, serverName string) (*grpc.ClientConn, error) {
	option, err := mtls.DialOption(serverName)
	if err != nil {
		return nil, err
	}
	return grpc.NewClient(address, option, grpc.WithStatsHandler(otelgrpc.NewClientHandler()))
}

func env(key, fallback string) string {
	if value := os.Getenv(key); value != "" {
		return value
	}
	return fallback
}
