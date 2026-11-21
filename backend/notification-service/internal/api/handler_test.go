package api

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/dipjyotimetia/distributed-systems/backend/notification-service/internal/consumer"
)

func TestNotificationReadsRequireToken(t *testing.T) {
	handler := NewHandler(stubEvents{}, "notification-token")

	health := httptest.NewRequest(http.MethodGet, "/health", nil)
	rec := httptest.NewRecorder()
	handler.ServeHTTP(rec, health)
	if rec.Code != http.StatusOK {
		t.Fatalf("health status %d", rec.Code)
	}

	denied := httptest.NewRequest(http.MethodGet, "/notifications/7", nil)
	rec = httptest.NewRecorder()
	handler.ServeHTTP(rec, denied)
	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("missing token status %d", rec.Code)
	}

	allowed := httptest.NewRequest(http.MethodGet, "/notifications/7", nil)
	allowed.Header.Set("X-Notification-Token", "notification-token")
	rec = httptest.NewRecorder()
	handler.ServeHTTP(rec, allowed)
	if rec.Code != http.StatusOK {
		t.Fatalf("authorized status %d, body %s", rec.Code, rec.Body.String())
	}
}

type stubEvents struct{}

func (stubEvents) Latest(context.Context, int) ([]*consumer.TransactionEvent, error) {
	return nil, nil
}

func (stubEvents) ForUser(context.Context, string, int) ([]*consumer.TransactionEvent, error) {
	return nil, nil
}
