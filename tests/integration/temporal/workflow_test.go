package integration

import (
	"errors"
	"strings"
	"testing"
	"time"

	"github.com/stretchr/testify/mock"
	"go.temporal.io/sdk/testsuite"
)

func TestJourneyOrdering(t *testing.T) {
	var suite testsuite.WorkflowTestSuite
	env := suite.NewTestWorkflowEnvironment()
	a := &Activities{}
	env.RegisterActivity(a)
	input := Input{User: "synthetic"}
	transfer := Transfer{From: 1, To: 2, ID: 3, UserID: 4}
	registered := env.OnActivity(a.Register, mock.Anything, input).Return(transfer.UserID, nil).Once()
	checking := env.OnActivity(a.CreateAccount, mock.Anything, input, "CHECKING", 100).Return(transfer.From, nil).Once().NotBefore(registered)
	savings := env.OnActivity(a.CreateAccount, mock.Anything, input, "SAVINGS", 20).Return(transfer.To, nil).Once().NotBefore(checking)
	unsubmitted := transfer
	unsubmitted.ID = 0
	commit := env.OnActivity(a.SubmitTransfer, mock.Anything, input, unsubmitted).Return(transfer.ID, nil).Once().NotBefore(savings)
	verify := env.OnActivity(a.Verify, mock.Anything, input, transfer).Return(nil).Once().NotBefore(commit)
	reject := env.OnActivity(a.Reject, mock.Anything, input, transfer).Return(nil).Once().NotBefore(verify)
	overdraftEvidence := env.OnActivity(a.Verify, mock.Anything, input, transfer).Return(nil).Once().NotBefore(reject)
	checks := env.OnActivity(a.VerifyChecks, mock.Anything, transfer).Return(nil).Once().NotBefore(overdraftEvidence)
	fraud := env.OnActivity(a.AssertFraudRejections, mock.Anything, input, transfer).Return(nil).Once().NotBefore(checks)
	fraudLedger := env.OnActivity(a.Verify, mock.Anything, input, transfer).Return(nil).Once().NotBefore(fraud)
	fraudEvidence := env.OnActivity(a.VerifyFraudRejections, mock.Anything, transfer).Return(BoundaryEvidence{Boundary: "Fraud and compliance"}, nil).Once().NotBefore(fraudLedger)
	kafka := env.OnActivity(a.VerifyKafka, mock.Anything, transfer).Return(BoundaryEvidence{Boundary: "Kafka"}, nil).Once().NotBefore(fraudEvidence)
	mongo := env.OnActivity(a.VerifyMongo, mock.Anything, transfer).Return(BoundaryEvidence{Boundary: "MongoDB"}, nil).Once().NotBefore(kafka)
	env.OnActivity(a.VerifyDeliveries, mock.Anything, transfer).Return(BoundaryEvidence{Boundary: "Delivery providers"}, nil).Once().NotBefore(mongo)
	env.RegisterDelayedCallback(func() { env.SignalWorkflow(Resume, nil) }, time.Second)
	env.ExecuteWorkflow(Journey, input)
	if err := env.GetWorkflowError(); err != nil {
		t.Fatal(err)
	}
	env.AssertExpectations(t)
}

func TestJourneyStopsOnRegistrationFailure(t *testing.T) {
	var suite testsuite.WorkflowTestSuite
	env := suite.NewTestWorkflowEnvironment()
	a := &Activities{}
	env.RegisterActivity(a)
	input := Input{User: "synthetic"}
	env.OnActivity(a.Register, mock.Anything, input).Return(int64(0), errors.New("write outcome unknown")).Once()
	env.ExecuteWorkflow(Journey, input)
	if err := env.GetWorkflowError(); err == nil || !strings.Contains(err.Error(), "write outcome unknown") {
		t.Fatalf("expected registration failure, got %v", err)
	}
	env.AssertNotCalled(t, "Verify", mock.Anything, mock.Anything, mock.Anything)
	env.AssertNotCalled(t, "Reject", mock.Anything, mock.Anything, mock.Anything)
	env.AssertExpectations(t)
}

func TestJourneyStopsOnAmbiguousTransfer(t *testing.T) {
	var suite testsuite.WorkflowTestSuite
	env := suite.NewTestWorkflowEnvironment()
	a := &Activities{}
	env.RegisterActivity(a)
	input := Input{User: "synthetic"}
	transfer := Transfer{UserID: 4, From: 1, To: 2}
	env.OnActivity(a.Register, mock.Anything, input).Return(transfer.UserID, nil).Once()
	env.OnActivity(a.CreateAccount, mock.Anything, input, "CHECKING", 100).Return(transfer.From, nil).Once()
	env.OnActivity(a.CreateAccount, mock.Anything, input, "SAVINGS", 20).Return(transfer.To, nil).Once()
	env.OnActivity(a.SubmitTransfer, mock.Anything, input, transfer).
		Return(int64(0), errors.New("transfer outcome unknown")).Once()

	env.ExecuteWorkflow(Journey, input)
	if err := env.GetWorkflowError(); err == nil || !strings.Contains(err.Error(), "transfer outcome unknown") {
		t.Fatalf("expected ambiguous transfer failure, got %v", err)
	}
	env.AssertNotCalled(t, "Verify", mock.Anything, mock.Anything, mock.Anything)
	env.AssertExpectations(t)
}
