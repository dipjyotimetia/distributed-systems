package integration

import (
	"context"
	"encoding/json"
	"fmt"
	"os/exec"
	"slices"
	"time"
)

type BoundaryEvidence struct {
	Boundary string `json:"boundary"`
	Expected string `json:"expected"`
	Observed string `json:"observed"`
}

type transactionEvent struct {
	ID        int64   `json:"transaction_id"`
	UserID    int64   `json:"user_id"`
	AccountID int64   `json:"account_id"`
	Type      string  `json:"transaction_type"`
	Status    string  `json:"status"`
	Amount    float64 `json:"amount"`
}

func expectedTransferEvent(transfer Transfer) transactionEvent {
	return transactionEvent{ID: transfer.ID, UserID: transfer.UserID, AccountID: transfer.From,
		Type: "TRANSFER", Status: "COMPLETED", Amount: 25}
}

func probeContainer(ctx context.Context, container string, dst any, args ...string) error {
	ctx, cancel := context.WithTimeout(ctx, 7*time.Second)
	defer cancel()
	output, err := exec.CommandContext(ctx, "docker", append([]string{"exec", container}, args...)...).Output()
	if err != nil {
		return fmt.Errorf("%s evidence probe failed", args[0])
	}
	if err := json.Unmarshal(output, dst); err != nil {
		return fmt.Errorf("%s returned invalid evidence", args[0])
	}
	return nil
}

func (a *Activities) VerifyKafka(ctx context.Context, transfer Transfer) (BoundaryEvidence, error) {
	var record struct {
		Partition, Offset, Start, End int
		Event                         transactionEvent
	}
	err := probeContainer(ctx, a.BrokerContainer, &record,
		"rpk", "topic", "consume", "transaction-events", "-X", "brokers=redpanda:9092",
		"--offset", "start", "--num", "1",
		"--format", `{"partition":%p,"offset":%o,"start":%[,"end":%],"event":%v}`)
	if err != nil {
		return BoundaryEvidence{}, err
	}
	// This scenario creates a fresh topic with one partition and exactly one successful mutation.
	if record.End-record.Start != 1 || record.Offset != record.Start || record.Event != expectedTransferEvent(transfer) {
		return BoundaryEvidence{}, fmt.Errorf("Kafka partition %d offset %d did not contain exactly one matching transfer", record.Partition, record.Offset)
	}
	return BoundaryEvidence{"Kafka", "one completed transfer event", fmt.Sprintf("one matching event at partition %d offset %d", record.Partition, record.Offset)}, nil
}

func (a *Activities) VerifyMongo(ctx context.Context, transfer Transfer) (BoundaryEvidence, error) {
	query := fmt.Sprintf(`const records = db.getSiblingDB("notifications").events.find({userid: NumberLong("%d")}).toArray();
		print(JSON.stringify(records.map(e => ({
			transaction_id: Number(e.transactionid), user_id: Number(e.userid),
			account_id: Number(e.accountid), transaction_type: e.transactiontype,
			status: e.status, amount: e.amount
		}))));`, transfer.UserID)
	var records []transactionEvent
	if err := probeContainer(ctx, a.MongoContainer, &records, "mongosh", "--quiet", "--eval", query); err != nil {
		return BoundaryEvidence{}, err
	}
	if len(records) != 1 || records[0] != expectedTransferEvent(transfer) {
		return BoundaryEvidence{}, fmt.Errorf("MongoDB did not contain one matching transfer document (found %d documents)", len(records))
	}
	return BoundaryEvidence{"MongoDB", "one completed transfer document", "one matching document"}, nil
}

type deliveryAttempt struct {
	Message string
	Status  int
}

func (a *Activities) VerifyDeliveries(ctx context.Context, transfer Transfer) (BoundaryEvidence, error) {
	var received map[string][]deliveryAttempt
	if err := call(ctx, a.Deliveries, "/deliveries", "", nil, 200, &received); err != nil {
		return BoundaryEvidence{}, err
	}
	expected := map[string]string{
		"slack":    fmt.Sprintf("[COMPLETED] Transaction %d: $25.00 TRANSFER", transfer.ID),
		"sendgrid": fmt.Sprintf("Transaction %d of $25.00 (TRANSFER) is COMPLETED.", transfer.ID),
		"twilio":   fmt.Sprintf("Apex Banking: your TRANSFER of $25.00 is COMPLETED (ref: %d)", transfer.ID),
	}
	for _, provider := range []string{"slack", "sendgrid", "twilio"} {
		attempts := received[provider]
		expectedAttempts := []deliveryAttempt{{Message: expected[provider], Status: 202}}
		if provider == a.FailedProvider {
			expectedAttempts = append([]deliveryAttempt{{Message: expected[provider], Status: 503}}, expectedAttempts...)
		}
		if !slices.Equal(attempts, expectedAttempts) {
			return BoundaryEvidence{}, fmt.Errorf("%s evidence does not match transfer %d (received %d attempts)", provider, transfer.ID, len(attempts))
		}
	}
	if a.FailedProvider != "" {
		return BoundaryEvidence{"Delivery providers", "one transient failure retried and one successful delivery per channel",
			a.FailedProvider + " returned HTTP 503, then HTTP 202. The other channels each returned HTTP 202 once."}, nil
	}
	return BoundaryEvidence{"Delivery providers", "one successful request per provider", "Slack, SendGrid and Twilio each received one matching request and returned HTTP 202"}, nil
}
