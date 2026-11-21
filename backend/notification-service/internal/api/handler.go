package api

import (
	"context"
	"encoding/json"
	"net/http"

	"github.com/dipjyotimetia/distributed-systems/backend/notification-service/internal/consumer"
	"github.com/prometheus/client_golang/prometheus/promhttp"
)

const defaultLimit = 20

type EventReader interface {
	Latest(context.Context, int) ([]*consumer.TransactionEvent, error)
	ForUser(context.Context, string, int) ([]*consumer.TransactionEvent, error)
}

// Handler wires HTTP routes to durable notification storage.
type Handler struct {
	events EventReader
}

func NewHandler(events EventReader) http.Handler {
	h := &Handler{events: events}
	root := http.NewServeMux()
	root.Handle("/metrics", promhttp.Handler())
	OpenAPIHandlerFromMux(h, root)
	return root
}

func (h *Handler) NotificationHealth(w http.ResponseWriter, _ *http.Request) {
	writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
}

func (h *Handler) ListNotifications(w http.ResponseWriter, r *http.Request, params ListNotificationsParams) {
	events, err := h.events.Latest(r.Context(), boundedLimit(params.Limit))
	writeEvents(w, events, err)
}

func (h *Handler) ListUserNotifications(w http.ResponseWriter, r *http.Request, userID string, params ListUserNotificationsParams) {
	events, err := h.events.ForUser(r.Context(), userID, boundedLimit(params.Limit))
	writeEvents(w, events, err)
}

func writeEvents(w http.ResponseWriter, events []*consumer.TransactionEvent, err error) {
	if err != nil {
		writeJSON(w, http.StatusServiceUnavailable, map[string]string{"error": "notification storage unavailable"})
		return
	}
	if events == nil {
		events = []*consumer.TransactionEvent{}
	}
	writeJSON(w, http.StatusOK, events)
}

func boundedLimit(limit *Limit) int {
	if limit == nil || *limit <= 0 {
		return defaultLimit
	}
	return min(*limit, 1000)
}

func writeJSON(w http.ResponseWriter, code int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	json.NewEncoder(w).Encode(v)
}
