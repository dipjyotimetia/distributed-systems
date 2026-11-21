package api

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/dipjyotimetia/distributed-systems/backend/notification-service/internal/consumer"
	"github.com/golang-jwt/jwt/v5"
)

var secret = []byte("notification-inbox-test-secret-key")

type memory struct {
	events map[string][]*consumer.TransactionEvent
}

func (m memory) Latest(context.Context, int) ([]*consumer.TransactionEvent, error) {
	return nil, nil
}

func (m memory) ForUser(_ context.Context, userID string, limit int) ([]*consumer.TransactionEvent, error) {
	events := m.events[userID]
	if len(events) > limit {
		events = events[:limit]
	}
	return events, nil
}

func TestInboxRequiresTheSignedInCustomer(t *testing.T) {
	handler := NewHandler(memory{events: map[string][]*consumer.TransactionEvent{
		"7": {{UserID: "7", TransactionType: "DEPOSIT", Status: "COMPLETED", Amount: 12}},
		"8": {{UserID: "8", TransactionType: "WITHDRAWAL", Status: "COMPLETED", Amount: 99}},
	}}, secret)

	missing := httptest.NewRecorder()
	handler.ServeHTTP(missing, httptest.NewRequest(http.MethodGet, "/notifications/me", nil))
	if missing.Code != http.StatusUnauthorized {
		t.Fatalf("missing token status %d", missing.Code)
	}

	invalid := httptest.NewRecorder()
	invalidRequest := httptest.NewRequest(http.MethodGet, "/notifications/me", nil)
	invalidRequest.Header.Set("Authorization", "Bearer not-a-token")
	handler.ServeHTTP(invalid, invalidRequest)
	if invalid.Code != http.StatusUnauthorized {
		t.Fatalf("invalid token status %d", invalid.Code)
	}

	owned := httptest.NewRecorder()
	ownedRequest := httptest.NewRequest(http.MethodGet, "/notifications/me", nil)
	ownedRequest.Header.Set("Authorization", "Bearer "+token(t, "7"))
	handler.ServeHTTP(owned, ownedRequest)
	if owned.Code != http.StatusOK {
		t.Fatalf("owned status %d body %s", owned.Code, owned.Body.String())
	}
	var events []consumer.TransactionEvent
	if err := json.Unmarshal(owned.Body.Bytes(), &events); err != nil {
		t.Fatal(err)
	}
	if len(events) != 1 || events[0].UserID.String() != "7" || events[0].Amount != 12 {
		t.Fatalf("events %+v", events)
	}
}

func token(t *testing.T, userID string) string {
	t.Helper()
	signed, err := jwt.NewWithClaims(jwt.SigningMethodHS256, jwt.MapClaims{
		"userId": userID,
		"exp":    time.Now().Add(time.Hour).Unix(),
	}).SignedString(secret)
	if err != nil {
		t.Fatal(err)
	}
	return signed
}
