package main

import (
	"context"
	"log"
	"net"
	"net/http"
	"os"
	"strconv"
	"strings"
	"time"

	"github.com/dipjyotimetia/distributed-systems/backend/kyc-service/gen/kyc/v1"
	"github.com/dipjyotimetia/distributed-systems/backend/kyc-service/internal/mtls"
	"github.com/dipjyotimetia/distributed-systems/backend/kyc-service/internal/rpc"
	"github.com/dipjyotimetia/distributed-systems/backend/kyc-service/internal/store"
	"github.com/dipjyotimetia/distributed-systems/backend/kyc-service/internal/workflow"
	"github.com/prometheus/client_golang/prometheus/promhttp"
	sdk "go.temporal.io/sdk/client"
	"go.temporal.io/sdk/worker"
	"google.golang.org/grpc"
)

func main() {
	ctx := context.Background()
	cases, err := store.Open(env("DATABASE_URL", "postgres://postgres:password@postgres:5432/banking_app?sslmode=disable"))
	if err != nil {
		log.Fatal(err)
	}
	defer cases.Close()
	if err := cases.Migrate(ctx); err != nil {
		log.Fatal(err)
	}
	if os.Getenv("KYC_MODE") == "worker" {
		runWorker(cases)
		return
	}
	runAPI(cases)
}

func runAPI(cases *store.Store) {
	var temporal sdk.Client
	if address := os.Getenv("TEMPORAL_ADDRESS"); address != "" {
		client, err := sdk.Dial(sdk.Options{HostPort: address})
		if err != nil {
			log.Println("temporal signal client unavailable:", err)
		} else {
			temporal = client
			defer client.Close()
		}
	}
	option, err := mtls.ServerOption()
	if err != nil {
		log.Fatal(err)
	}
	var options []grpc.ServerOption
	if option != nil {
		options = append(options, option)
	}
	server := grpc.NewServer(options...)
	kycv1.RegisterKycServer(server, &rpc.Server{Store: cases, Secret: []byte(env("JWT_SECRET", "")), Operators: operators(), Temporal: temporal})
	go func() {
		mux := http.NewServeMux()
		mux.HandleFunc("/api/v1/health", func(w http.ResponseWriter, _ *http.Request) { w.WriteHeader(http.StatusOK) })
		mux.Handle("/metrics", promhttp.Handler())
		log.Println("kyc metrics", env("METRICS_PORT", "9092"))
		log.Fatal(http.ListenAndServe(":"+env("METRICS_PORT", "9092"), mux))
	}()
	lis, err := net.Listen("tcp", ":"+env("GRPC_PORT", "8091"))
	if err != nil {
		log.Fatal(err)
	}
	log.Println("kyc gRPC", lis.Addr())
	log.Fatal(server.Serve(lis))
}

func runWorker(cases *store.Store) {
	client, err := sdk.Dial(sdk.Options{HostPort: env("TEMPORAL_ADDRESS", "disputes-temporal:7233")})
	if err != nil {
		log.Fatal(err)
	}
	defer client.Close()
	work := worker.New(client, "kyc-review", worker.Options{})
	work.RegisterWorkflow(workflow.Review)
	work.RegisterActivity(&workflow.Activities{Store: cases})
	go claim(cases, client)
	log.Fatal(work.Run(worker.InterruptCh()))
}

func claim(cases *store.Store, client sdk.Client) {
	for {
		ids, err := cases.Claim(context.Background())
		if err == nil {
			for _, id := range ids {
				_, err = client.ExecuteWorkflow(context.Background(), sdk.StartWorkflowOptions{ID: id, TaskQueue: "kyc-review"}, workflow.Review, workflow.Input{CaseID: id})
				if err != nil {
					log.Println("workflow start", id, err)
				}
			}
		}
		time.Sleep(time.Second)
	}
}

func operators() map[int64]bool {
	values := map[int64]bool{}
	for _, item := range strings.Split(os.Getenv("KYC_OPERATOR_IDS"), ",") {
		id, err := strconv.ParseInt(strings.TrimSpace(item), 10, 64)
		if err == nil {
			values[id] = true
		}
	}
	return values
}

func env(key, fallback string) string {
	if value := os.Getenv(key); value != "" {
		return value
	}
	return fallback
}
