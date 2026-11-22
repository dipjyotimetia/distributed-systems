package consumer

import (
	"context"
	"encoding/json"
	"fmt"
	"strconv"
	"sync"
	"time"

	"github.com/dipjyotimetia/distributed-systems/backend/notification-service/internal/events"
	"github.com/twmb/avro"
	"github.com/twmb/franz-go/pkg/sr"
)

// AvroDecoder decodes Confluent-wire-format Avro payloads using Schema Registry
// into types generated from contracts/avro (see scripts/avro-gen.sh).
type AvroDecoder struct {
	client *sr.Client
	header sr.ConfluentHeader

	mu      sync.RWMutex
	schemas map[int]*avro.Schema
}

func NewAvroDecoder(registryURLs ...string) (*AvroDecoder, error) {
	if len(registryURLs) == 0 || registryURLs[0] == "" {
		return nil, fmt.Errorf("schema registry URL is required")
	}
	client, err := sr.NewClient(sr.URLs(registryURLs...))
	if err != nil {
		return nil, fmt.Errorf("configure schema registry client: %w", err)
	}
	return &AvroDecoder{client: client, schemas: make(map[int]*avro.Schema)}, nil
}

func (d *AvroDecoder) schema(ctx context.Context, id int) (*avro.Schema, error) {
	d.mu.RLock()
	if cached, ok := d.schemas[id]; ok {
		d.mu.RUnlock()
		return cached, nil
	}
	d.mu.RUnlock()

	registered, err := d.client.SchemaByID(ctx, id)
	if err != nil {
		return nil, fmt.Errorf("fetch schema id %d: %w", id, err)
	}
	parsed, err := avro.Parse(registered.Schema)
	if err != nil {
		return nil, fmt.Errorf("parse schema id %d: %w", id, err)
	}
	d.mu.Lock()
	d.schemas[id] = parsed
	d.mu.Unlock()
	return parsed, nil
}

func (d *AvroDecoder) DecodeTransaction(ctx context.Context, value []byte) (*TransactionEvent, error) {
	id, payload, err := d.header.DecodeID(value)
	if err != nil {
		return nil, err
	}
	schema, err := d.schema(ctx, id)
	if err != nil {
		return nil, err
	}
	var raw events.TransactionEvent
	if _, err := schema.Decode(payload, &raw); err != nil {
		return nil, fmt.Errorf("decode transaction avro: %w", err)
	}
	if raw.TransactionID == 0 || raw.Status == "" {
		return nil, fmt.Errorf("invalid transaction event")
	}
	ts, err := parseAvroTime(raw.Timestamp)
	if err != nil {
		return nil, err
	}
	event := &TransactionEvent{
		TransactionID:   json.Number(strconv.FormatInt(raw.TransactionID, 10)),
		UserID:          json.Number(strconv.FormatInt(raw.UserID, 10)),
		Amount:          raw.Amount,
		TransactionType: raw.TransactionType,
		Status:          raw.Status,
		Timestamp:       ts,
	}
	if raw.AccountID != nil {
		n := json.Number(strconv.FormatInt(*raw.AccountID, 10))
		event.AccountID = &n
	}
	return event, nil
}

func (d *AvroDecoder) DecodePayment(ctx context.Context, value []byte) (events.PaymentEvent, error) {
	id, payload, err := d.header.DecodeID(value)
	if err != nil {
		return events.PaymentEvent{}, err
	}
	schema, err := d.schema(ctx, id)
	if err != nil {
		return events.PaymentEvent{}, err
	}
	var raw events.PaymentEvent
	if _, err := schema.Decode(payload, &raw); err != nil {
		return events.PaymentEvent{}, fmt.Errorf("decode payment avro: %w", err)
	}
	return raw, nil
}

func parseAvroTime(value string) (time.Time, error) {
	for _, layout := range []string{time.RFC3339Nano, time.RFC3339, "2006-01-02T15:04:05Z", "2006-01-02T15:04:05"} {
		if ts, err := time.Parse(layout, value); err == nil {
			return ts, nil
		}
	}
	return time.Time{}, fmt.Errorf("invalid timestamp %q", value)
}

func isConfluentAvro(value []byte) bool {
	return len(value) >= 5 && value[0] == 0
}
