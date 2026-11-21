package cases

import (
	"testing"
	"time"
)

func TestStage(t *testing.T) {
	closed := Case{Status: "RESOLVED"}
	if closed.Stage() != "CLOSED" {
		t.Fatalf("resolved stage=%s", closed.Stage())
	}
	waiting := Case{
		Status: "OPEN",
		Evidence: &Evidence{
			ID: "1", Prompt: "why", RequestedAt: time.Now(), Deadline: time.Now().Add(time.Hour),
		},
	}
	if waiting.Stage() != "WAITING_FOR_EVIDENCE" {
		t.Fatalf("waiting stage=%s", waiting.Stage())
	}
	done := time.Now()
	review := Case{
		Status: "ESCALATED",
		Evidence: &Evidence{
			ID: "1", Prompt: "why", Response: strPtr("because"), ClosedAt: &done,
			RequestedAt: time.Now(), Deadline: time.Now(),
		},
	}
	if review.Stage() != "OPERATOR_REVIEW" {
		t.Fatalf("review stage=%s", review.Stage())
	}
}

func strPtr(v string) *string { return &v }
