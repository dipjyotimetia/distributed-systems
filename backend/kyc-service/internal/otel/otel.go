package otelsetup

import (
	"context"
	"os"
	"strings"
	"time"

	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/exporters/otlp/otlptrace/otlptracegrpc"
	"go.opentelemetry.io/otel/propagation"
	"go.opentelemetry.io/otel/sdk/resource"
	sdktrace "go.opentelemetry.io/otel/sdk/trace"
	semconv "go.opentelemetry.io/otel/semconv/v1.37.0"
)

// Init configures a TracerProvider when OTEL_EXPORTER_OTLP_ENDPOINT is set.
// Returns a shutdown function (may be a no-op).
func Init(ctx context.Context, defaultService string) (func(context.Context) error, error) {
	otel.SetTextMapPropagator(propagation.TraceContext{})
	if os.Getenv("OTEL_SDK_DISABLED") == "true" || os.Getenv("OTEL_EXPORTER_OTLP_ENDPOINT") == "" {
		return func(context.Context) error { return nil }, nil
	}

	exporter, err := otlptracegrpc.New(ctx)
	if err != nil {
		return nil, err
	}
	service := os.Getenv("OTEL_SERVICE_NAME")
	if service == "" {
		service = defaultService
	}
	namespace := "banking-app"
	for part := range strings.SplitSeq(os.Getenv("OTEL_RESOURCE_ATTRIBUTES"), ",") {
		kv := strings.SplitN(strings.TrimSpace(part), "=", 2)
		if len(kv) == 2 && kv[0] == "service.namespace" && kv[1] != "" {
			namespace = kv[1]
		}
	}
	// NewSchemaless avoids Schema URL conflicts with resource.Default()
	// (SDK default schema vs pinned semconv import).
	res, err := resource.Merge(resource.Default(), resource.NewSchemaless(
		semconv.ServiceName(service),
		semconv.ServiceNamespace(namespace),
	))
	if err != nil {
		return nil, err
	}
	provider := sdktrace.NewTracerProvider(
		sdktrace.WithBatcher(exporter),
		sdktrace.WithResource(res),
	)
	otel.SetTracerProvider(provider)
	return func(shutdownCtx context.Context) error {
		ctx, cancel := context.WithTimeout(shutdownCtx, 5*time.Second)
		defer cancel()
		return provider.Shutdown(ctx)
	}, nil
}
