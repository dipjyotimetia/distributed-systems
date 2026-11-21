package ledger

import (
	"context"
	"net"
	"testing"

	ledgerv1 "github.com/dipjyotimetia/distributed-systems/backend/cards-service/gen/ledger/v1"
	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials/insecure"
	"google.golang.org/grpc/metadata"
)

type captureLedger struct {
	ledgerv1.UnimplementedAccountLedgerServer
	signature string
}

func (c *captureLedger) ApplyMovement(ctx context.Context, _ *ledgerv1.MovementRequest) (*ledgerv1.MovementResult, error) {
	values := metadata.ValueFromIncomingContext(ctx, "x-movement-signature")
	if len(values) > 0 {
		c.signature = values[0]
	}
	return &ledgerv1.MovementResult{}, nil
}

func TestWithdrawSendsMovementSignature(t *testing.T) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	recorded := &captureLedger{}
	server := grpc.NewServer()
	ledgerv1.RegisterAccountLedgerServer(server, recorded)
	go server.Serve(listener)
	t.Cleanup(server.Stop)
	conn, err := grpc.NewClient(listener.Addr().String(), grpc.WithTransportCredentials(insecure.NewCredentials()))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = conn.Close() })
	client := Client{Ledger: ledgerv1.NewAccountLedgerClient(conn), Secret: "secret"}
	if err := client.Withdraw(context.Background(), 7, 4, "10.50", "op-1"); err != nil {
		t.Fatal(err)
	}
	if recorded.signature != Sign("secret", "op-1", "COMMIT", 7, 4, "10.50") {
		t.Fatalf("signature %s", recorded.signature)
	}
}
