package consumer

import (
	"context"
	"errors"
	"testing"

	"github.com/twmb/franz-go/pkg/kgo"
	"github.com/twmb/franz-go/pkg/sasl"
)

func TestNewWithAuthPlainConfiguresSASL(t *testing.T) {
	c, err := NewWithAuth(
		[]string{"banking-kafka:9092"},
		"transaction-events",
		"notification-service",
		nil,
		AuthConfig{
			Mechanism: "PLAIN",
			Username:  "banking",
			Password:  "secret",
		},
	)
	if err != nil {
		t.Fatalf("NewWithAuth returned error: %v", err)
	}
	defer c.Close()

	mechanisms := c.client.OptValue(kgo.SASL).([]sasl.Mechanism)
	if len(mechanisms) != 1 || mechanisms[0].Name() != "PLAIN" {
		t.Fatal("expected the PLAIN SASL mechanism")
	}
}

func TestNewWithAuthRequiresPlainCredentials(t *testing.T) {
	_, err := NewWithAuth(
		[]string{"banking-kafka:9092"},
		"transaction-events",
		"notification-service",
		nil,
		AuthConfig{Mechanism: "PLAIN"},
	)
	if err == nil {
		t.Fatal("expected missing credentials error")
	}
}

func TestNewWithAuthRejectsUnsupportedMechanism(t *testing.T) {
	_, err := NewWithAuth(
		[]string{"banking-kafka:9092"},
		"transaction-events",
		"notification-service",
		nil,
		AuthConfig{
			Mechanism: "SCRAM-SHA-256",
			Username:  "banking",
			Password:  "secret",
		},
	)
	if err == nil {
		t.Fatal("expected unsupported mechanism error")
	}
}

type testStore struct {
	inserted bool
	err      error
}

func (s testStore) Insert(_ context.Context, _ *TransactionEvent) (bool, error) {
	return s.inserted, s.err
}

func TestDeliverSkipsFanOutWhenPersistenceFailsOrEventAlreadyExists(t *testing.T) {
	event := &TransactionEvent{TransactionID: "10", Status: "COMPLETED"}
	storeError := errors.New("database unavailable")
	for _, tc := range []struct {
		name  string
		store testStore
		err   error
	}{
		{"duplicate", testStore{}, nil},
		{"storage error", testStore{err: storeError}, storeError},
	} {
		t.Run(tc.name, func(t *testing.T) {
			c := &Consumer{store: tc.store, Buffer: &RingBuffer{}}
			// No notifier is set. Invoking fan-out would fail this test.
			if err := c.deliver(context.Background(), event); !errors.Is(err, tc.err) {
				t.Fatalf("deliver returned %v, expected %v", err, tc.err)
			}
			if events := c.Buffer.Latest(1); len(events) != 0 {
				t.Fatal("unexpected event in the API buffer")
			}
		})
	}
}
