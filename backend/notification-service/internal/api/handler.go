package api

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"strconv"
	"strings"

	"github.com/dipjyotimetia/distributed-systems/backend/notification-service/internal/consumer"
	"github.com/golang-jwt/jwt/v5"
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
	secret []byte
}

func NewHandler(events EventReader, secret []byte) http.Handler {
	h := &Handler{events: events, secret: secret}
	r := mux.NewRouter()
	r.HandleFunc("/health", h.health).Methods(http.MethodGet)
	r.Handle("/metrics", promhttp.Handler()).Methods(http.MethodGet)
	r.HandleFunc("/notifications/me", h.me).Methods(http.MethodGet)
	r.HandleFunc("/notifications", h.list).Methods(http.MethodGet)
	r.HandleFunc("/notifications/{user_id}", h.byUser).Methods(http.MethodGet)
	return r
}

func (h *Handler) health(w http.ResponseWriter, _ *http.Request) {
	writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
}

func (h *Handler) me(w http.ResponseWriter, r *http.Request) {
	userID, ok := customerID(r, h.secret)
	if !ok {
		writeJSON(w, http.StatusUnauthorized, map[string]string{"error": "sign in to continue"})
		return
	}
	events, err := h.events.ForUser(r.Context(), userID, parseLimit(r, defaultLimit))
	writeEvents(w, events, err)
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

func customerID(r *http.Request, secret []byte) (string, bool) {
	scheme, token, ok := strings.Cut(r.Header.Get("Authorization"), " ")
	if !ok || !strings.EqualFold(scheme, "Bearer") || token == "" || len(secret) == 0 {
		return "", false
	}
	claims := jwt.MapClaims{}
	_, err := jwt.ParseWithClaims(token, claims, func(*jwt.Token) (any, error) {
		return secret, nil
	}, jwt.WithValidMethods([]string{"HS256"}), jwt.WithExpirationRequired())
	if err != nil {
		return "", false
	}
	id, err := strconv.ParseInt(fmt.Sprint(claims["userId"]), 10, 64)
	if err != nil || id < 1 {
		return "", false
	}
	return strconv.FormatInt(id, 10), true
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
