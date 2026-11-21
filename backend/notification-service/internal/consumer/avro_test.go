package consumer

import (
	"context"
	"os"
	"path/filepath"
	"runtime"
	"testing"

	"github.com/twmb/avro"
	"github.com/twmb/franz-go/pkg/kgo"
	"github.com/twmb/franz-go/pkg/sr"

	"github.com/dipjyotimetia/distributed-systems/backend/notification-service/internal/events"
)

func TestDecodeTransactionAvroRoundTrip(t *testing.T) {
	schema := avro.MustParse(readContract(t, "transaction-event.avsc"))
	accountID := int64(4)
	payload, err := schema.Encode(&events.TransactionEvent{
		TransactionID:   3,
		AccountID:       &accountID,
		UserID:          7,
		Amount:          12.5,
		TransactionType: "DEPOSIT",
		Status:          "COMPLETED",
		Timestamp:       "2026-11-20T09:30:00Z",
	})
	if err != nil {
		t.Fatalf("encode: %v", err)
	}
	var header sr.ConfluentHeader
	wire, err := header.AppendEncode(nil, 42, nil)
	if err != nil {
		t.Fatalf("header: %v", err)
	}
	wire = append(wire, payload...)

	decoder := &AvroDecoder{schemas: map[int]*avro.Schema{42: schema}}
	event, err := decoder.DecodeTransaction(context.Background(), wire)
	if err != nil {
		t.Fatalf("decode: %v", err)
	}
	if event.TransactionID != "3" || event.UserID != "7" || event.Status != "COMPLETED" ||
		event.TransactionType != "DEPOSIT" || event.Amount != 12.5 ||
		event.AccountID == nil || event.AccountID.String() != "4" {
		t.Fatalf("unexpected event: %+v", event)
	}
}

func TestDecodePaymentAvroSettled(t *testing.T) {
	schema := avro.MustParse(readContract(t, "payment-event.avsc"))
	const paymentID = "597a4c24-019f-4ed6-884f-bf7cf8486f65"
	const eventID = "6ebeca05-61f1-4cae-bdd1-bade0e5beccd"
	payload, err := schema.Encode(&events.PaymentEvent{
		Version:     1,
		EventID:     eventID,
		PaymentID:   paymentID,
		CustomerID:  9,
		AmountMinor: 125,
		Currency:    "AUD",
		Status:      "SETTLED",
		OccurredAt:  "2026-11-20T04:32:00Z",
	})
	if err != nil {
		t.Fatalf("encode: %v", err)
	}
	var header sr.ConfluentHeader
	wire, err := header.AppendEncode(nil, 7, nil)
	if err != nil {
		t.Fatalf("header: %v", err)
	}
	wire = append(wire, payload...)

	c := &Consumer{
		paymentTopic: "payment-events.v1",
		avro:         &AvroDecoder{schemas: map[int]*avro.Schema{7: schema}},
	}
	event, err := c.decode(context.Background(), &kgo.Record{
		Topic: c.paymentTopic,
		Key:   []byte(paymentID),
		Value: wire,
	})
	if err != nil || event == nil || event.EventID != eventID || event.PaymentID != paymentID ||
		event.AmountMinor != 125 || event.TransactionType != "OUTBOUND_PAYMENT" {
		t.Fatalf("settled avro payment decode failed: %+v, %v", event, err)
	}
}

func readContract(t *testing.T, name string) string {
	t.Helper()
	_, file, _, ok := runtime.Caller(0)
	if !ok {
		t.Fatal("caller path unavailable")
	}
	path := filepath.Join(filepath.Dir(file), "..", "..", "..", "..", "contracts", "avro", name)
	body, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read %s: %v", path, err)
	}
	return string(body)
}
