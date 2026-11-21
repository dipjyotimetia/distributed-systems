package integration

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"os/exec"
	"time"

	"go.temporal.io/sdk/workflow"
)

func RecoveryJourney(ctx workflow.Context, input Input) ([]BoundaryEvidence, error) {
	var a *Activities
	writes := workflow.WithActivityOptions(ctx, mutationOptions())
	reads := workflow.WithActivityOptions(ctx, evidenceOptions())
	var transfer Transfer
	if err := workflow.ExecuteActivity(writes, a.Register, input).Get(ctx, &transfer.UserID); err != nil {
		return nil, err
	}
	if err := workflow.ExecuteActivity(writes, a.CreateAccount, input, "CHECKING", 100).Get(ctx, &transfer.From); err != nil {
		return nil, err
	}
	if err := workflow.ExecuteActivity(writes, a.CreateAccount, input, "SAVINGS", 20).Get(ctx, &transfer.To); err != nil {
		return nil, err
	}
	if err := workflow.ExecuteActivity(writes, a.ControlConsumer, "stop").Get(ctx, nil); err != nil {
		return nil, err
	}
	if err := workflow.ExecuteActivity(writes, a.SubmitTransfer, input, transfer).Get(ctx, &transfer.ID); err != nil {
		return nil, err
	}
	var broker BoundaryEvidence
	if err := workflow.ExecuteActivity(reads, a.VerifyKafka, transfer).Get(ctx, &broker); err != nil {
		return nil, err
	}
	if err := workflow.ExecuteActivity(writes, a.ControlConsumer, "start").Get(ctx, nil); err != nil {
		return nil, err
	}
	if err := workflow.ExecuteActivity(reads, a.VerifyCommittedOffset, 1).Get(ctx, nil); err != nil {
		return nil, err
	}
	if err := workflow.ExecuteActivity(writes, a.RestartBroker).Get(ctx, nil); err != nil {
		return nil, err
	}
	if err := workflow.ExecuteActivity(reads, a.VerifyCommittedOffset, 1).Get(ctx, nil); err != nil {
		return nil, err
	}
	if err := workflow.ExecuteActivity(writes, a.ControlConsumer, "restart").Get(ctx, nil); err != nil {
		return nil, err
	}
	if err := workflow.ExecuteActivity(writes, a.PublishDuplicate, transfer).Get(ctx, nil); err != nil {
		return nil, err
	}
	if err := workflow.ExecuteActivity(reads, a.VerifyCommittedOffset, 2).Get(ctx, nil); err != nil {
		return nil, err
	}
	var mongo, deliveries BoundaryEvidence
	if err := workflow.ExecuteActivity(reads, a.VerifyMongo, transfer).Get(ctx, &mongo); err != nil {
		return nil, err
	}
	if err := workflow.ExecuteActivity(reads, a.VerifyDeliveries, transfer).Get(ctx, &deliveries); err != nil {
		return nil, err
	}
	return []BoundaryEvidence{broker, mongo, deliveries, {
		Boundary: "Kafka consumer group", Expected: "committed offset survives broker restart and duplicate commits after consumer restart",
		Observed: "consumer resumed with one stored event and retried only the transiently failed channel after committing offsets 1 and 2",
	}}, nil
}

func (a *Activities) ControlConsumer(ctx context.Context, action string) error {
	switch action {
	case "stop", "start", "restart":
		if err := exec.CommandContext(ctx, "docker", action, a.ConsumerContainer).Run(); err != nil {
			return fmt.Errorf("notification consumer %s failed", action)
		}
		return nil
	default:
		return fmt.Errorf("unsupported consumer operation")
	}
}

func (a *Activities) RestartBroker(ctx context.Context) error {
	if err := exec.CommandContext(ctx, "docker", "restart", a.BrokerContainer).Run(); err != nil {
		return fmt.Errorf("Kafka broker restart failed")
	}
	return nil
}

func (a *Activities) PublishDuplicate(ctx context.Context, transfer Transfer) error {
	event := struct {
		transactionEvent
		Timestamp time.Time `json:"timestamp"`
	}{expectedTransferEvent(transfer), time.Now().UTC()}
	payload, err := json.Marshal(event)
	if err != nil {
		return fmt.Errorf("encode duplicate transfer event")
	}
	cmd := exec.CommandContext(ctx, "docker", "exec", "-i", a.BrokerContainer, "rpk", "topic",
		"produce", "transaction-events", "-X", "brokers=redpanda:9092", "--key", fmt.Sprint(transfer.ID))
	cmd.Stdin = bytes.NewReader(append(payload, '\n'))
	if err := cmd.Run(); err != nil {
		return fmt.Errorf("publish duplicate transfer event failed")
	}
	return nil
}

func (a *Activities) VerifyCommittedOffset(ctx context.Context, offset int) error {
	ticker := time.NewTicker(time.Second)
	defer ticker.Stop()
	for {
		err := a.checkCommittedOffset(ctx, offset)
		if err == nil {
			return nil
		}
		select {
		case <-ctx.Done():
			return err
		case <-ticker.C:
		}
	}
}

func (a *Activities) checkCommittedOffset(ctx context.Context, offset int) error {
	var groups []struct {
		Partitions []struct {
			Topic     string
			Partition int
			Current   int `json:"current_offset"`
			End       int `json:"log_end_offset"`
		}
	}
	if err := probeContainer(ctx, a.BrokerContainer, &groups, "rpk", "group", "describe",
		"notification-service", "-X", "brokers=redpanda:9092", "--format", "json"); err != nil {
		return err
	}
	if len(groups) != 1 || len(groups[0].Partitions) != 1 {
		return fmt.Errorf("notification consumer group has %d groups or no assigned partition", len(groups))
	}
	partition := groups[0].Partitions[0]
	if partition.Topic != "transaction-events" || partition.Partition != 0 || partition.Current != offset || partition.End != offset {
		return fmt.Errorf("notification consumer has not committed next offset %d", offset)
	}
	return nil
}
