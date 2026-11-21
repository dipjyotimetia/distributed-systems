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
	root := http.NewServeMux()
	root.Handle("/metrics", promhttp.Handler())
	OpenAPIHandlerFromMux(h, root)
	return root
}

func (h *Handler) NotificationHealth(w http.ResponseWriter, _ *http.Request) {
	writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
}

func (h *Handler) ListMyNotifications(w http.ResponseWriter, r *http.Request, params ListMyNotificationsParams) {
	userID, ok := customerID(r, h.secret)
	if !ok {
		writeJSON(w, http.StatusUnauthorized, map[string]string{"error": "sign in to continue"})
		return
	}
	events, err := h.events.ForUser(r.Context(), userID, boundedLimit(params.Limit))
	writeEvents(w, events, err)
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
