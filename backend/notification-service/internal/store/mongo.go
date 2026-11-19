package store

import (
	"context"
	"fmt"
	"time"

	"go.mongodb.org/mongo-driver/bson"
	"go.mongodb.org/mongo-driver/mongo"
	"go.mongodb.org/mongo-driver/mongo/options"

	"github.com/speedscale/microsvc/notification-service/internal/consumer"
)

type MongoStore struct {
	coll *mongo.Collection
}

func NewMongoStore(ctx context.Context, uri, db, collection string) (*MongoStore, error) {
	ctx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()

	client, err := mongo.Connect(ctx, options.Client().ApplyURI(uri))
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
	return &MongoStore{coll: coll}, nil
}

func (s *MongoStore) Insert(ctx context.Context, evt *consumer.TransactionEvent) (bool, error) {
	_, err := s.coll.InsertOne(ctx, evt)
	if mongo.IsDuplicateKeyError(err) {
		return false, nil
	}
	return err == nil, err
}

func (s *MongoStore) Latest(ctx context.Context, n int) ([]*consumer.TransactionEvent, error) {
	opts := options.Find().
		SetSort(bson.D{{Key: "timestamp", Value: -1}}).
		SetLimit(int64(n))

	cursor, err := s.coll.Find(ctx, bson.D{}, opts)
	if err != nil {
		return nil, err
	}
	defer cursor.Close(ctx)

	var events []*consumer.TransactionEvent
	if err := cursor.All(ctx, &events); err != nil {
		return nil, err
	}
	return events, nil
}

func (s *MongoStore) ForUser(ctx context.Context, userID string, n int) ([]*consumer.TransactionEvent, error) {
	opts := options.Find().
		SetSort(bson.D{{Key: "timestamp", Value: -1}}).
		SetLimit(int64(n))

	cursor, err := s.coll.Find(ctx, bson.D{{Key: "userid", Value: userID}}, opts)
	if err != nil {
		return nil, err
	}
	defer cursor.Close(ctx)

	var events []*consumer.TransactionEvent
	if err := cursor.All(ctx, &events); err != nil {
		return nil, err
	}
	return events, nil
}
