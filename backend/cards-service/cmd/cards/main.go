package main

import (
	"context"
	"log"
	"net"
	"net/http"
	"os"
	"time"

	cardsv1 "github.com/dipjyotimetia/distributed-systems/backend/cards-service/gen/cards/v1"
	"github.com/dipjyotimetia/distributed-systems/backend/cards-service/internal/ledger"
	"github.com/dipjyotimetia/distributed-systems/backend/cards-service/internal/mtls"
	"github.com/dipjyotimetia/distributed-systems/backend/cards-service/internal/service"
	"github.com/dipjyotimetia/distributed-systems/backend/cards-service/internal/store"
	fraudv1 "github.com/dipjyotimetia/distributed-systems/backend/fraud-service/gen/fraud/v1"
	kycv1 "github.com/dipjyotimetia/distributed-systems/backend/kyc-service/gen/kyc/v1"
	"github.com/prometheus/client_golang/prometheus/promhttp"
	"google.golang.org/grpc"
	"google.golang.org/grpc/metadata"
)

func main() {
	ctx := context.Background()
	db, err := store.Open(env("DATABASE_URL", "postgres://postgres:password@postgres:5432/banking_app?sslmode=disable"))
	if err != nil {
		log.Fatal(err)
	}
	defer db.Close()
	if err := db.Migrate(ctx); err != nil {
		log.Fatal(err)
	}
	kycConn, err := dial(env("KYC_GRPC_ADDR", "kyc-service:8091"), "kyc-service", "")
	if err != nil {
		log.Fatal(err)
	}
	defer kycConn.Close()
	fraudToken := os.Getenv("FRAUD_CLIENT_SECRET")
	if fraudToken == "" {
		log.Fatal("FRAUD_CLIENT_SECRET is required")
	}
	fraudConn, err := dial(env("FRAUD_GRPC_ADDR", "fraud-service:8080"), "fraud-service", fraudToken)
	if err != nil {
		log.Fatal(err)
	}
	defer fraudConn.Close()
	option, err := mtls.ServerOption()
	if err != nil {
		log.Fatal(err)
	}
	var options []grpc.ServerOption
	if option != nil {
		options = append(options, option)
	}
	server := grpc.NewServer(options...)
	secret := env("JWT_SECRET", "")
	movementSecret := os.Getenv("MOVEMENT_SECRET")
	if movementSecret == "" || movementSecret == secret {
		log.Fatal("MOVEMENT_SECRET is required and must differ from JWT_SECRET")
	}
	cardsv1.RegisterCardsServer(server, &service.Server{
		Repo: db, Secret: []byte(secret),
		Ledger: ledger.Client{Base: env("ACCOUNTS_SERVICE_URL", "http://accounts-service:8080"), Secret: movementSecret, HTTP: &http.Client{Timeout: 4 * time.Second}},
		Fraud:  fraudv1.NewFraudCheckerClient(fraudConn),
		Kyc:    kycv1.NewKycClient(kycConn),
	})
	go func() {
		mux := http.NewServeMux()
		mux.HandleFunc("/health", func(w http.ResponseWriter, _ *http.Request) { w.WriteHeader(http.StatusOK) })
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

func dial(address, serverName, fraudToken string) (*grpc.ClientConn, error) {
	option, err := mtls.DialOption(serverName)
	if err != nil {
		return nil, err
	}
	options := []grpc.DialOption{option}
	if fraudToken != "" {
		options = append(options, grpc.WithUnaryInterceptor(func(ctx context.Context, method string, req, reply any, cc *grpc.ClientConn, invoker grpc.UnaryInvoker, opts ...grpc.CallOption) error {
			ctx = metadata.AppendToOutgoingContext(ctx, "x-fraud-token", fraudToken)
			return invoker(ctx, method, req, reply, cc, opts...)
		}))
	}
	return grpc.NewClient(address, options...)
}

func env(key, fallback string) string {
	if value := os.Getenv(key); value != "" {
		return value
	}
	return fallback
}
