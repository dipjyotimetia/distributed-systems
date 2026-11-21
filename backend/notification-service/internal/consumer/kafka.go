package consumer

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/twmb/franz-go/pkg/kgo"
	"github.com/twmb/franz-go/pkg/sasl"
	"github.com/twmb/franz-go/pkg/sasl/plain"

	"github.com/dipjyotimetia/distributed-systems/backend/notification-service/internal/metrics"
)

const ringSize = 1000

// TransactionEvent mirrors the upstream transaction-events message schema.
// The Java producer sends transaction_id and user_id as JSON numbers and
// account_id as a number or null, so we use json.Number (nullable via pointer).
type TransactionEvent struct {
	TransactionID   json.Number  `json:"transaction_id,omitempty" bson:"transactionid,omitempty"`
	PaymentID       string       `json:"payment_id,omitempty" bson:"paymentid,omitempty"`
	EventID         string       `json:"event_id,omitempty" bson:"eventid,omitempty"`
	Currency        string       `json:"currency,omitempty" bson:"currency,omitempty"`
	AmountMinor     int64        `json:"amount_minor,omitempty" bson:"amountminor,omitempty"`
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

// Store atomically persists events and their delivery intents.
type Store interface {
	Insert(ctx context.Context, evt *TransactionEvent) (inserted bool, err error)
	// Quarantine durably records a poison Kafka record before the offset is committed.
	Quarantine(ctx context.Context, topic string, partition int32, offset int64, key, value []byte, reason string) error
}

// Consumer reads from Kafka and populates both the ring buffer and a durable store.
type Consumer struct {
	client        *kgo.Client
	topic         string
	paymentTopic  string
	Buffer        *RingBuffer
	store         Store
	avro          *AvroDecoder
	commitRecords func(context.Context, ...*kgo.Record) error
}

type AuthConfig struct {
	Mechanism string
	Username  string
	Password  string
}

func New(brokers []string, topic, groupID string, store Store) *Consumer {
	c, err := NewWithAuth(brokers, topic, groupID, store, AuthConfig{}, "")
	if err != nil {
		panic(err)
	}
	return c
}

func NewWithAuth(brokers []string, topic, groupID string, store Store, auth AuthConfig, paymentTopic string, schemaRegistryURLs ...string) (*Consumer, error) {
	topics := []string{topic}
	if paymentTopic != "" {
		if paymentTopic == topic {
			return nil, fmt.Errorf("payment events require a separate Kafka topic")
		}
		topics = append(topics, paymentTopic)
	}
	opts := []kgo.Opt{
		kgo.SeedBrokers(brokers...),
		kgo.ConsumeTopics(topics...),
		kgo.ConsumerGroup(groupID),
		kgo.ConsumeResetOffset(kgo.NewOffset().AtStart()),
		kgo.FetchMinBytes(1),
		kgo.FetchMaxBytes(10_000_000),
		kgo.DisableAutoCommit(),
		kgo.BlockRebalanceOnPoll(),
	}
	if auth.Mechanism != "" {
		mechanism, err := saslMechanism(auth)
		if err != nil {
			return nil, err
		}
		opts = append(opts, kgo.SASL(mechanism))
	}

	client, err := kgo.NewClient(opts...)
	if err != nil {
		return nil, fmt.Errorf("configure Kafka consumer: %w", err)
	}
	var decoder *AvroDecoder
	if len(schemaRegistryURLs) > 0 && schemaRegistryURLs[0] != "" {
		decoder, err = NewAvroDecoder(schemaRegistryURLs...)
		if err != nil {
			client.Close()
			return nil, err
		}
	}
	c := &Consumer{
		client:       client,
		topic:        topic,
		paymentTopic: paymentTopic,
		Buffer:       &RingBuffer{},
		store:        store,
		avro:         decoder,
	}
	c.commitRecords = client.CommitRecords
	return c, nil
}

func saslMechanism(auth AuthConfig) (sasl.Mechanism, error) {
	switch strings.ToUpper(auth.Mechanism) {
	case "PLAIN":
		if auth.Username == "" || auth.Password == "" {
			return nil, fmt.Errorf("KAFKA_SASL_USERNAME and KAFKA_SASL_PASSWORD are required for PLAIN")
		}
		return plain.Auth{User: auth.Username, Pass: auth.Password}.AsMechanism(), nil
	default:
		return nil, fmt.Errorf("unsupported Kafka SASL mechanism %q", auth.Mechanism)
	}
}

// Run blocks, consuming messages until ctx is cancelled.
func (c *Consumer) Run(ctx context.Context) error {
	if c.store == nil {
		return fmt.Errorf("notification event store is required")
	}
	log.Printf("kafka consumer started (topic=%s)", c.topic)
	for {
		fetches := c.client.PollRecords(ctx, 1)
		err := fetches.Err()
		if err == nil {
			fetches.EachRecord(func(record *kgo.Record) {
				err = c.process(ctx, record)
			})
		}
		c.client.AllowRebalance()
		if ctx.Err() != nil {
			return nil
		}
		if err != nil {
			metrics.ConsumeErrors.Inc()
			return err
		}
	}
}

func (c *Consumer) process(ctx context.Context, message *kgo.Record) error {
	event, err := c.decode(ctx, message)
	if err != nil {
		metrics.ConsumeErrors.Inc()
		log.Printf("invalid notification event (partition=%d offset=%d): %v", message.Partition, message.Offset, err)
		quarantineCtx, cancel := context.WithTimeout(ctx, 10*time.Second)
		defer cancel()
		if qerr := c.store.Quarantine(quarantineCtx, message.Topic, message.Partition, message.Offset, message.Key, message.Value, err.Error()); qerr != nil {
			return fmt.Errorf("quarantine invalid notification event: %w", qerr)
		}
		metrics.EventsQuarantined.Inc()
	} else if event != nil {
		if err := c.deliver(ctx, event); err != nil {
			return err
		}
	}
	if err := c.commitRecords(ctx, message); err != nil {
		return fmt.Errorf("commit transaction event offset: %w", err)
	}
	return nil
}

var paymentIDPattern = regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`)

func (c *Consumer) decode(ctx context.Context, message *kgo.Record) (*TransactionEvent, error) {
	if c.paymentTopic != "" && message.Topic == c.paymentTopic {
		return c.decodePayment(ctx, message)
	}
	if c.avro != nil && isConfluentAvro(message.Value) {
		return c.avro.DecodeTransaction(ctx, message.Value)
	}
	var event TransactionEvent
	if err := json.Unmarshal(message.Value, &event); err != nil || event.TransactionID == "" || event.Status == "" {
		return nil, fmt.Errorf("invalid transaction event")
	}
	return &event, nil
}

func (c *Consumer) decodePayment(ctx context.Context, message *kgo.Record) (*TransactionEvent, error) {
	var (
		version     int
		eventID     string
		paymentID   string
		customerID  int64
		amountMinor int64
		currency    string
		status      string
		occurredAt  time.Time
	)
	if c.avro != nil && isConfluentAvro(message.Value) {
		raw, err := c.avro.DecodePayment(ctx, message.Value)
		if err != nil {
			return nil, err
		}
		occurredAt, err = parseAvroTime(raw.OccurredAt)
		if err != nil {
			return nil, err
		}
		version = int(raw.Version)
		eventID = raw.EventID
		paymentID = raw.PaymentID
		customerID = raw.CustomerID
		amountMinor = raw.AmountMinor
		currency = raw.Currency
		status = raw.Status
	} else {
		var payment struct {
			Version     int
			EventID     string
			PaymentID   string
			CustomerID  int64
			AmountMinor int64
			Currency    string
			Status      string
			OccurredAt  time.Time
		}
		if err := json.Unmarshal(message.Value, &payment); err != nil {
			return nil, err
		}
		version = payment.Version
		eventID = payment.EventID
		paymentID = payment.PaymentID
		customerID = payment.CustomerID
		amountMinor = payment.AmountMinor
		currency = payment.Currency
		status = payment.Status
		occurredAt = payment.OccurredAt
	}
	if version != 1 || !paymentIDPattern.MatchString(eventID) ||
		!paymentIDPattern.MatchString(paymentID) || string(message.Key) != paymentID ||
		customerID < 1 || amountMinor < 1 || amountMinor > 999999999999 ||
		currency != "AUD" || occurredAt.IsZero() {
		return nil, fmt.Errorf("invalid payment event")
	}
	switch status {
	case "PENDING", "SUBMITTED", "ACCEPTED", "REJECTED", "REVIEW_REQUIRED":
		return nil, nil
	case "SETTLED":
		return &TransactionEvent{
			PaymentID: paymentID, EventID: eventID, Currency: currency,
			UserID: json.Number(strconv.FormatInt(customerID, 10)), AmountMinor: amountMinor,
			Amount: float64(amountMinor) / 100, TransactionType: "OUTBOUND_PAYMENT",
			Status: status, Timestamp: occurredAt,
		}, nil
	default:
		return nil, fmt.Errorf("unknown payment status")
	}
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
	return nil
}

// Close shuts the Kafka client.
func (c *Consumer) Close() error {
	c.client.CloseAllowingRebalance()
	return nil
}
