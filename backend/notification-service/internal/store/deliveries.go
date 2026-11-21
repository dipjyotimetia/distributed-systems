package store

import (
	"context"
	"errors"
	"log"
	"time"

	"go.mongodb.org/mongo-driver/v2/bson"
	"go.mongodb.org/mongo-driver/v2/mongo"
	"go.mongodb.org/mongo-driver/v2/mongo/options"

	"github.com/dipjyotimetia/distributed-systems/backend/notification-service/internal/consumer"
	"github.com/dipjyotimetia/distributed-systems/backend/notification-service/internal/metrics"
	"github.com/dipjyotimetia/distributed-systems/backend/notification-service/internal/notify"
)

const maxAttempts = 8

type delivery struct {
	State    string        `bson:"state"`
	DueAt    time.Time     `bson:"dueAt"`
	Attempts int           `bson:"attempts"`
	Lease    bson.ObjectID `bson:"lease,omitempty"`
}

type eventDocument struct {
	consumer.TransactionEvent `bson:",inline"`
	ID                        bson.ObjectID       `bson:"_id,omitempty"`
	Deliveries                map[string]delivery `bson:"deliveries"`
}

func createDeliveryIndexes(ctx context.Context, collection *mongo.Collection) error {
	for _, channel := range notify.Channels {
		prefix := "deliveries." + channel + "."
		_, err := collection.Indexes().CreateOne(ctx, mongo.IndexModel{
			Keys: bson.D{{Key: prefix + "state", Value: 1}, {Key: prefix + "dueAt", Value: 1}},
		})
		if err != nil {
			return err
		}
	}
	return nil
}

// RunDeliveries resumes pending intents even when Kafka is unavailable.
// The due time also serves as a lease so another worker can recover an interrupted attempt.
func (s *MongoStore) RunDeliveries(ctx context.Context) {
	notifier := notify.NewNotifier()
	ticker := time.NewTicker(time.Second)
	defer ticker.Stop()
	for {
		for _, channel := range notify.Channels {
			for _, collection := range []*mongo.Collection{s.coll, s.payments} {
				if ctx.Err() != nil {
					return
				}
				if err := deliverOne(ctx, collection, channel, notifier); err != nil && ctx.Err() == nil {
					log.Printf("notification delivery worker (%s): %v", channel, err)
				}
			}
		}
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
		}
	}
}

func deliverOne(ctx context.Context, collection *mongo.Collection, channel string, notifier *notify.Notifier) error {
	prefix := "deliveries." + channel + "."
	now := time.Now().UTC()
	lease := bson.NewObjectID()
	filter := bson.M{prefix + "state": "PENDING", prefix + "dueAt": bson.M{"$lte": now}}
	claim := bson.M{
		"$set": bson.M{prefix + "dueAt": now.Add(time.Minute), prefix + "lease": lease},
		"$inc": bson.M{prefix + "attempts": 1},
	}
	var event eventDocument
	claimCtx, cancel := context.WithTimeout(ctx, 5*time.Second)
	err := collection.FindOneAndUpdate(claimCtx, filter, claim, options.FindOneAndUpdate().
		SetReturnDocument(options.After).SetSort(bson.D{{Key: prefix + "dueAt", Value: 1}})).Decode(&event)
	cancel()
	if errors.Is(err, mongo.ErrNoDocuments) {
		return nil
	}
	if err != nil {
		return err
	}

	state := "EXHAUSTED"
	attempt := event.Deliveries[channel].Attempts
	reference := event.TransactionID.String()
	if event.PaymentID != "" {
		reference = event.PaymentID
	}
	if attempt <= maxAttempts {
		err = notifier.Deliver(ctx, channel, notify.TransactionInfo{
			TransactionID: reference, Currency: event.Currency, UserID: event.UserID.String(),
			Amount: event.Amount, Type: event.TransactionType, Status: event.Status,
		})
		var providerStatus notify.ProviderStatus
		switch {
		case err == nil:
			state = "DELIVERED"
		case attempt < maxAttempts && (!errors.As(err, &providerStatus) || providerStatus.Retriable()):
			state = "PENDING"
		}
	}

	resultCtx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	result, err := collection.UpdateOne(resultCtx, bson.M{"_id": event.ID, prefix + "lease": lease}, bson.M{
		"$set":   bson.M{prefix + "state": state, prefix + "dueAt": time.Now().UTC().Add(time.Second << min(attempt, maxAttempts))},
		"$unset": bson.M{prefix + "lease": ""},
	})
	if err != nil {
		return err
	}
	if result.ModifiedCount == 1 {
		switch state {
		case "PENDING":
			metrics.DeliveryRetries.WithLabelValues(channel).Inc()
		case "EXHAUSTED":
			metrics.DeliveryExhausted.WithLabelValues(channel).Inc()
			log.Printf("notification delivery exhausted reference=%s channel=%s attempts=%d", reference, channel, attempt)
		}
	}
	return nil
}
