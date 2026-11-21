package main

import (
	"context"
	"log"
	"net/http"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"github.com/dipjyotimetia/distributed-systems/backend/notification-service/internal/api"
	"github.com/dipjyotimetia/distributed-systems/backend/notification-service/internal/consumer"
	"github.com/dipjyotimetia/distributed-systems/backend/notification-service/internal/store"
)

func main() {
	port := envOrDefault("HTTP_PORT", "8080")
	brokers := strings.Split(envOrDefault("KAFKA_BROKERS", "banking-kafka:9092"), ",")
	topic := envOrDefault("KAFKA_TOPIC", "transaction-events")
	mongoURI := envOrDefault("MONGO_URI", "mongodb://banking-mongodb:27017")
	kafkaAuth := consumer.AuthConfig{
		Mechanism: os.Getenv("KAFKA_SASL_MECHANISM"),
		Username:  os.Getenv("KAFKA_SASL_USERNAME"),
		Password:  os.Getenv("KAFKA_SASL_PASSWORD"),
	}

	ctx, cancel := signal.NotifyContext(context.Background(), syscall.SIGTERM, syscall.SIGINT)
	defer cancel()

	mongoStore, err := store.NewMongoStore(ctx, mongoURI, "notifications", "events")
	if err != nil {
		log.Fatalf("MongoDB startup failed: %v", err)
	}

	go mongoStore.RunDeliveries(ctx)

	paymentTopic := os.Getenv("PAYMENT_KAFKA_TOPIC")
	newConsumer := func() (*consumer.Consumer, error) {
		return consumer.NewWithAuth(brokers, topic, "notification-service", mongoStore, kafkaAuth, paymentTopic)
	}
	c, err := newConsumer()
	if err != nil {
		log.Fatalf("Kafka configuration error: %v", err)
	}
	go func(c *consumer.Consumer) {
		for {
			runErr := c.Run(ctx)
			c.Close()
			if runErr == nil || ctx.Err() != nil {
				return
			}
			log.Printf("Kafka consumer stopped, reconnecting: %v", runErr)
			select {
			case <-ctx.Done():
				return
			case <-time.After(time.Second):
			}
			next, err := newConsumer()
			if err != nil {
				log.Fatalf("Kafka configuration error: %v", err)
			}
			c = next
		}
	}(c)

	srv := &http.Server{
		Addr:    ":" + port,
		Handler: api.NewHandler(mongoStore, []byte(os.Getenv("JWT_SECRET"))),
	}

	go func() {
		<-ctx.Done()
		log.Println("shutting down HTTP server")
		srv.Shutdown(context.Background())
	}()

	log.Printf("HTTP server listening on :%s", port)
	if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
		log.Fatalf("HTTP server error: %v", err)
	}
}

func envOrDefault(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}
