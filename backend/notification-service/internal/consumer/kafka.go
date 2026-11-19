package consumer

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"strings"
	"sync"
	"time"

	kafka "github.com/segmentio/kafka-go"
	"github.com/segmentio/kafka-go/sasl"
	"github.com/segmentio/kafka-go/sasl/plain"

	"github.com/speedscale/microsvc/notification-service/internal/metrics"
	"github.com/speedscale/microsvc/notification-service/internal/notify"
)

const ringSize = 1000

// TransactionEvent mirrors the upstream transaction-events message schema.
// The Java producer sends transaction_id and user_id as JSON numbers and
// account_id as a number or null, so we use json.Number (nullable via pointer).
type TransactionEvent struct {
	TransactionID   json.Number  `json:"transaction_id" bson:"transactionid"`
	AccountID       *json.Number `json:"account_id" bson:"accountid"`
	UserID          json.Number  `json:"user_id" bson:"userid"`
	Amount          float64      `json:"amount" bson:"amount"`
	TransactionType string       `json:"transaction_type" bson:"transactiontype"`
	Status          string       `json:"status" bson:"status"`
	Timestamp       time.Time    `json:"timestamp" bson:"timestamp"`
}

// RingBuffer holds the last N events in insertion order.
type RingBuffer struct {
	mu     sync.Mutex
	events [ringSize]*TransactionEvent
	head   int // next write position
	count  int
}

func (r *RingBuffer) Push(e *TransactionEvent) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.events[r.head] = e
	r.head = (r.head + 1) % ringSize
	if r.count < ringSize {
		r.count++
	}
	metrics.BufferEvents.Set(float64(r.count))
}

// Latest returns up to n events, newest first.
func (r *RingBuffer) Latest(n int) []*TransactionEvent {
	r.mu.Lock()
	defer r.mu.Unlock()
	if n > r.count {
		n = r.count
	}
	out := make([]*TransactionEvent, 0, n)
	for i := 1; i <= n; i++ {
		idx := (r.head - i + ringSize) % ringSize
		out = append(out, r.events[idx])
	}
	return out
}

// ForUser returns up to n events for a specific userID, newest first.
func (r *RingBuffer) ForUser(userID string, n int) []*TransactionEvent {
	r.mu.Lock()
	defer r.mu.Unlock()
	out := make([]*TransactionEvent, 0)
	for i := 1; i <= r.count; i++ {
		idx := (r.head - i + ringSize) % ringSize
		e := r.events[idx]
		if e != nil && e.UserID.String() == userID {
			out = append(out, e)
			if len(out) >= n {
				break
			}
		}
	}
	return out
}

// Store persists events for durable retrieval.
type Store interface {
	Insert(ctx context.Context, evt *TransactionEvent) (inserted bool, err error)
}

// Consumer reads from Kafka and populates both the ring buffer and a durable store.
type Consumer struct {
	reader   *kafka.Reader
	Buffer   *RingBuffer
	store    Store
	notifier *notify.Notifier
}

type AuthConfig struct {
	Mechanism string
	Username  string
	Password  string
}

func New(brokers []string, topic, groupID string, store Store) *Consumer {
	c, err := NewWithAuth(brokers, topic, groupID, store, AuthConfig{})
	if err != nil {
		panic(err)
	}
	return c
}

func NewWithAuth(brokers []string, topic, groupID string, store Store, auth AuthConfig) (*Consumer, error) {
	cfg := kafka.ReaderConfig{
		Brokers:  brokers,
		Topic:    topic,
		GroupID:  groupID,
		MinBytes: 1,
		MaxBytes: 10e6,
	}

	if auth.Mechanism != "" {
		mechanism, err := saslMechanism(auth)
		if err != nil {
			return nil, err
		}
		cfg.Dialer = &kafka.Dialer{
			Timeout:       10 * time.Second,
			DualStack:     true,
			SASLMechanism: mechanism,
		}
	}

	r := kafka.NewReader(cfg)
	return &Consumer{
		reader:   r,
		Buffer:   &RingBuffer{},
		store:    store,
		notifier: notify.NewNotifier(),
	}, nil
}

func saslMechanism(auth AuthConfig) (sasl.Mechanism, error) {
	switch strings.ToUpper(auth.Mechanism) {
	case "PLAIN":
		if auth.Username == "" || auth.Password == "" {
			return nil, fmt.Errorf("KAFKA_SASL_USERNAME and KAFKA_SASL_PASSWORD are required for PLAIN")
		}
		return plain.Mechanism{
			Username: auth.Username,
			Password: auth.Password,
		}, nil
	default:
		return nil, fmt.Errorf("unsupported Kafka SASL mechanism %q", auth.Mechanism)
	}
}

// Run blocks, consuming messages until ctx is cancelled.
func (c *Consumer) Run(ctx context.Context) error {
	if c.store == nil {
		return fmt.Errorf("notification event store is required")
	}
	log.Printf("kafka consumer started (topic=%s)", c.reader.Config().Topic)
	for {
		message, err := c.reader.FetchMessage(ctx)
		if err == nil {
			err = c.process(ctx, message)
		}
		if ctx.Err() != nil {
			return nil
		}
		if err != nil {
			metrics.ConsumeErrors.Inc()
			return err
		}
	}
}

func (c *Consumer) process(ctx context.Context, message kafka.Message) error {
	var event TransactionEvent
	if err := json.Unmarshal(message.Value, &event); err != nil || event.TransactionID == "" || event.Status == "" {
		metrics.ConsumeErrors.Inc()
		log.Printf("invalid transaction event (partition=%d offset=%d)", message.Partition, message.Offset)
	} else {
		if err := c.deliver(ctx, &event); err != nil {
			return err
		}
	}
	if err := c.reader.CommitMessages(ctx, message); err != nil {
		return fmt.Errorf("commit transaction event offset: %w", err)
	}
	return nil
}

func (c *Consumer) deliver(ctx context.Context, event *TransactionEvent) error {
	persistCtx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	inserted, err := c.store.Insert(persistCtx, event)
	if err != nil {
		return fmt.Errorf("persist transaction event: %w", err)
	}
	if !inserted {
		return nil
	}
	metrics.EventsConsumed.WithLabelValues(event.TransactionType, event.Status).Inc()
	c.Buffer.Push(event)
	c.notifier.FanOut(ctx, notify.TransactionInfo{
		TransactionID: event.TransactionID.String(),
		UserID:        event.UserID.String(),
		Amount:        event.Amount,
		Type:          event.TransactionType,
		Status:        event.Status,
	})
	return nil
}

// Close shuts the Kafka reader.
func (c *Consumer) Close() error {
	return c.reader.Close()
}
