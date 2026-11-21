package store

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"sync/atomic"
	"testing"
	"time"

	"go.mongodb.org/mongo-driver/v2/bson"

	"github.com/dipjyotimetia/distributed-systems/backend/notification-service/internal/consumer"
	"github.com/dipjyotimetia/distributed-systems/backend/notification-service/internal/notify"
)

func TestDeliveryResumesAfterRestartAndBrokerRedelivery(t *testing.T) {
	uri := os.Getenv("NOTIFICATION_TEST_MONGO_URI")
	if uri == "" {
		t.Skip("set NOTIFICATION_TEST_MONGO_URI to a disposable MongoDB server")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	var requests atomic.Int32
	provider := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		if requests.Add(1) == 1 {
			w.WriteHeader(http.StatusServiceUnavailable)
		} else {
			w.WriteHeader(http.StatusAccepted)
		}
	}))
	defer provider.Close()
	t.Setenv("SLACK_WEBHOOK_URL", provider.URL)

	database := "notification_test_" + bson.NewObjectID().Hex()
	initial, err := NewMongoStore(ctx, uri, database, "events")
	if err != nil {
		t.Fatal(err)
	}
	defer initial.coll.Database().Client().Disconnect(ctx)
	defer initial.coll.Database().Drop(ctx)
	event := &consumer.TransactionEvent{TransactionID: "18", UserID: json.Number("71"),
		Status: "COMPLETED", TransactionType: "DEPOSIT", Amount: 10, Timestamp: time.Now().UTC()}
	if inserted, err := initial.Insert(ctx, event); err != nil || !inserted {
		t.Fatalf("persist intake: inserted=%t err=%v", inserted, err)
	}
	if err := deliverOne(ctx, initial.coll, "slack", notify.NewNotifier()); err != nil {
		t.Fatal(err)
	}

	restarted, err := NewMongoStore(ctx, uri, database, "events")
	if err != nil {
		t.Fatal(err)
	}
	defer restarted.coll.Database().Client().Disconnect(ctx)
	if inserted, err := restarted.Insert(ctx, event); err != nil || inserted {
		t.Fatalf("deduplicate intake: inserted=%t err=%v", inserted, err)
	}
	filter := bson.M{"deliveries.slack.state": "PENDING", "deliveries.slack.attempts": 1}
	update := bson.M{"$set": bson.M{"deliveries.slack.dueAt": time.Now().Add(-time.Second)}}
	if result, err := restarted.coll.UpdateOne(ctx, filter, update); err != nil || result.ModifiedCount != 1 {
		t.Fatalf("expected durable pending retry after restart: %v", err)
	}
	for range 2 {
		if err := deliverOne(ctx, restarted.coll, "slack", notify.NewNotifier()); err != nil {
			t.Fatal(err)
		}
	}
	var stored eventDocument
	if err := restarted.coll.FindOne(ctx, bson.M{}).Decode(&stored); err != nil {
		t.Fatal(err)
	}
	if stored.Deliveries["slack"].State != "DELIVERED" || requests.Load() != 2 {
		t.Fatalf("expected one failed and one successful call, got %d and state %s",
			requests.Load(), stored.Deliveries["slack"].State)
	}
}
