package main

import (
	"context"
	"log"
	"net"
	"net/http"
	"os"
	"time"

	"crypto/tls"
	"crypto/x509"
	"github.com/prometheus/client_golang/prometheus/promhttp"
	"go.opentelemetry.io/contrib/instrumentation/google.golang.org/grpc/otelgrpc"
	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials"
	"google.golang.org/grpc/keepalive"

	"github.com/dipjyotimetia/distributed-systems/backend/fraud-service/internal/fraud"
	otelsetup "github.com/dipjyotimetia/distributed-systems/backend/fraud-service/internal/otel"

	fraudv1 "github.com/dipjyotimetia/distributed-systems/backend/fraud-service/gen/fraud/v1"
)

func main() {
	port := envOrDefault("GRPC_PORT", "8080")
	metricsPort := envOrDefault("METRICS_PORT", "9091")

	shutdown, err := otelsetup.Init(context.Background(), "fraud-service")
	if err != nil {
		log.Fatalf("otel init: %v", err)
	}
	defer func() { _ = shutdown(context.Background()) }()

	go serveMetrics(metricsPort)

	lis, err := net.Listen("tcp", ":"+port)
	if err != nil {
		log.Fatalf("failed to listen on :%s: %v", port, err)
	}

	// ebpf observability: force connections to recycle after 1 minute so nettap can observe the connection
	// start and allow rrpairs to be correctly identified and parsed
	options := []grpc.ServerOption{
		grpc.StatsHandler(otelgrpc.NewServerHandler()),
		grpc.KeepaliveParams(keepalive.ServerParameters{
			MaxConnectionAge:      1 * time.Minute,
			MaxConnectionAgeGrace: 5 * time.Second,
		}),
	}
	if os.Getenv("MTLS_ENABLED") == "true" {
		certificate, err := tls.LoadX509KeyPair(os.Getenv("MTLS_CERT"), os.Getenv("MTLS_KEY"))
		if err != nil {
			log.Fatal(err)
		}
		pem, err := os.ReadFile(os.Getenv("MTLS_CA"))
		if err != nil {
			log.Fatal(err)
		}
		pool := x509.NewCertPool()
		pool.AppendCertsFromPEM(pem)
		options = append(options, grpc.Creds(credentials.NewTLS(&tls.Config{
			Certificates: []tls.Certificate{certificate},
			ClientAuth:   tls.RequireAndVerifyClientCert,
			ClientCAs:    pool,
			MinVersion:   tls.VersionTLS12,
		})))
	}
	srv := grpc.NewServer(options...)
	fraudv1.RegisterFraudCheckerServer(srv, &fraud.Checker{})

	log.Printf("fraud-service listening on :%s", port)
	if err := srv.Serve(lis); err != nil {
		log.Fatalf("server exited: %v", err)
	}
}

// serveMetrics exposes Prometheus metrics on a dedicated HTTP port so the
// gRPC port stays protocol-pure.
func serveMetrics(port string) {
	mux := http.NewServeMux()
	mux.Handle("/metrics", promhttp.Handler())
	log.Printf("fraud-service metrics on :%s/metrics", port)
	if err := http.ListenAndServe(":"+port, mux); err != nil {
		log.Printf("metrics server stopped: %v", err)
	}
}

func envOrDefault(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}
