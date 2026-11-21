package store

import (
	"context"
	"fmt"
	"slices"
	"strconv"
	"time"

	"go.mongodb.org/mongo-driver/v2/bson"
	"go.mongodb.org/mongo-driver/v2/mongo"
	"go.mongodb.org/mongo-driver/v2/mongo/options"
	"go.mongodb.org/mongo-driver/v2/mongo/writeconcern"

	"github.com/dipjyotimetia/distributed-systems/backend/notification-service/internal/consumer"
	"github.com/dipjyotimetia/distributed-systems/backend/notification-service/internal/notify"
)

type MongoStore struct {
	coll     *mongo.Collection
	payments *mongo.Collection
}

func NewMongoStore(ctx context.Context, uri, db, collection string) (*MongoStore, error) {
	ctx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()

	client, err := mongo.Connect(options.Client().ApplyURI(uri).SetWriteConcern(writeconcern.Journaled()))
	if err != nil {
		return nil, err
	}
	if err := client.Ping(ctx, nil); err != nil {
		_ = client.Disconnect(context.Background())
		return nil, err
	}

	coll := client.Database(db).Collection(collection)

	// Indexes for query patterns
	_, err = coll.Indexes().CreateMany(ctx, []mongo.IndexModel{
		{Keys: bson.D{{Key: "transactionid", Value: 1}, {Key: "status", Value: 1}}, Options: options.Index().SetUnique(true)},
		{Keys: bson.D{{Key: "userid", Value: 1}, {Key: "timestamp", Value: -1}}},
		{Keys: bson.D{{Key: "timestamp", Value: -1}}},
	})

	if err != nil {
		_ = client.Disconnect(context.Background())
		return nil, fmt.Errorf("create notification indexes: %w", err)
	}
	payments := client.Database(db).Collection(collection + "_payments")
	if _, err := payments.Indexes().CreateMany(ctx, []mongo.IndexModel{
		{Keys: bson.D{{Key: "eventid", Value: 1}}, Options: options.Index().SetUnique(true)},
		{Keys: bson.D{{Key: "userid", Value: 1}, {Key: "timestamp", Value: -1}}},
		{Keys: bson.D{{Key: "timestamp", Value: -1}}},
	}); err != nil {
		_ = client.Disconnect(context.Background())
		return nil, fmt.Errorf("create payment notification index: %w", err)
	}
	store := &MongoStore{coll: coll, payments: payments}
	for _, collection := range []*mongo.Collection{coll, payments} {
		if err := createDeliveryIndexes(ctx, collection); err != nil {
			_ = client.Disconnect(context.Background())
			return nil, fmt.Errorf("create delivery indexes: %w", err)
		}
	}
	return store, nil
}

func (s *MongoStore) Insert(ctx context.Context, evt *consumer.TransactionEvent) (bool, error) {
	collection := s.coll
	if evt.PaymentID != "" {
		collection = s.payments
	}
	deliveries := make(map[string]delivery, len(notify.Channels))
	for _, channel := range notify.Channels {
		deliveries[channel] = delivery{State: "PENDING", DueAt: time.Now().UTC()}
	}
	_, err := collection.InsertOne(ctx, eventDocument{TransactionEvent: *evt, Deliveries: deliveries})
	if mongo.IsDuplicateKeyError(err) {
		return false, nil
	}
	return err == nil, err
}

func (s *MongoStore) Latest(ctx context.Context, n int) ([]*consumer.TransactionEvent, error) {
	return s.read(ctx, bson.D{}, n)
}

func (s *MongoStore) ForUser(ctx context.Context, userID string, n int) ([]*consumer.TransactionEvent, error) {
	id, err := strconv.ParseInt(userID, 10, 64)
	if err != nil || id <= 0 {
		return nil, nil
	}
	return s.read(ctx, bson.D{{Key: "userid", Value: bson.D{{Key: "$in", Value: bson.A{id, userID}}}}}, n)
}

func (s *MongoStore) read(ctx context.Context, filter bson.D, n int) ([]*consumer.TransactionEvent, error) {
	ctx, cancel := context.WithTimeout(ctx, 3*time.Second)
	defer cancel()
	opts := options.Find().SetSort(bson.D{{Key: "timestamp", Value: -1}}).SetLimit(int64(n))
	var combined []*consumer.TransactionEvent
	for _, collection := range []*mongo.Collection{s.coll, s.payments} {
		cursor, err := collection.Find(ctx, filter, opts)
		if err != nil {
			return nil, err
		}
		var events []*consumer.TransactionEvent
		if err := cursor.All(ctx, &events); err != nil {
			return nil, err
		}
		combined = append(combined, events...)
	}
	slices.SortStableFunc(combined, func(a, b *consumer.TransactionEvent) int {
		return b.Timestamp.Compare(a.Timestamp)
	})
	return combined[:min(n, len(combined))], nil
}
