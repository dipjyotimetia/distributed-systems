package api

import (
	"context"
	"crypto/subtle"
	"encoding/json"
	"net/http"
	"strconv"

	"github.com/dipjyotimetia/distributed-systems/backend/notification-service/internal/consumer"
	"github.com/gorilla/mux"
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

func NewHandler(events EventReader, token string) http.Handler {
	h := &Handler{events: events}
	r := mux.NewRouter()
	r.HandleFunc("/health", h.health).Methods(http.MethodGet)
	r.Handle("/metrics", promhttp.Handler()).Methods(http.MethodGet)
	r.HandleFunc("/notifications", h.list).Methods(http.MethodGet)
	r.HandleFunc("/notifications/{user_id}", h.byUser).Methods(http.MethodGet)
	return requireToken(token, r)
}

func requireToken(token string, next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/health" || r.URL.Path == "/metrics" {
			next.ServeHTTP(w, r)
			return
		}
		supplied := r.Header.Get("X-Notification-Token")
		if token == "" || subtle.ConstantTimeCompare([]byte(supplied), []byte(token)) != 1 {
			writeJSON(w, http.StatusUnauthorized, map[string]string{"error": "notification token is required"})
			return
		}
		next.ServeHTTP(w, r)
	})
}

func (h *Handler) health(w http.ResponseWriter, _ *http.Request) {
	writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
}

func (h *Handler) list(w http.ResponseWriter, r *http.Request) {
	limit := parseLimit(r, defaultLimit)
	events, err := h.events.Latest(r.Context(), limit)
	writeEvents(w, events, err)
}

func (h *Handler) byUser(w http.ResponseWriter, r *http.Request) {
	userID := mux.Vars(r)["user_id"]
	limit := parseLimit(r, defaultLimit)
	events, err := h.events.ForUser(r.Context(), userID, limit)
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

func parseLimit(r *http.Request, def int) int {
	if s := r.URL.Query().Get("limit"); s != "" {
		if n, err := strconv.Atoi(s); err == nil && n > 0 {
			return min(n, 1000)
		}
	}
	return def
}

func writeJSON(w http.ResponseWriter, code int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	json.NewEncoder(w).Encode(v)
}
