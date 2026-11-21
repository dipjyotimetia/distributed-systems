package httpapi

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"os"
	"strconv"
	"strings"
	"time"
	"unicode/utf8"

	transactionsv1 "github.com/dipjyotimetia/distributed-systems/backend/disputes-service/gen/transactions/v1"
	"github.com/dipjyotimetia/distributed-systems/backend/disputes-service/internal/cases"
	"github.com/golang-jwt/jwt/v5"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/metadata"
	"google.golang.org/grpc/status"
)

type caseStore interface {
	ByTransfer(context.Context, int64, int64) (cases.Case, error)
	ByID(context.Context, int64, int64) (cases.Case, error)
	ByIDInternal(context.Context, int64) (cases.Case, error)
	Create(context.Context, int64, int64, string, time.Duration) (cases.Case, bool, error)
	CustomerCases(context.Context, int64) ([]cases.Case, error)
	OperationsQueue(context.Context, int64, int64) ([]cases.Case, error)
	Resolve(context.Context, int64, int64, string) (cases.Case, error)
	RequestEvidence(context.Context, int64, int64, string, string) (cases.Case, error)
	RespondToEvidence(context.Context, int64, int64, string, string, string) (cases.Case, error)
	SaveAiDraft(context.Context, int64, json.RawMessage) (cases.Case, error)
}

type TransferView struct {
	ID     int64
	UserID int64
	Type   string
	Status string
}

type TransferLookup interface {
	Lookup(ctx context.Context, authorization string, id int64) (TransferView, error)
}

type Query struct {
	Client transactionsv1.TransactionQueryClient
}

func (q Query) Lookup(ctx context.Context, authorization string, id int64) (TransferView, error) {
	ctx = metadata.AppendToOutgoingContext(ctx, "authorization", authorization)
	view, err := q.Client.GetTransaction(ctx, &transactionsv1.GetTransactionRequest{Id: id})
	if err != nil {
		return TransferView{}, err
	}
	return TransferView{ID: view.Id, UserID: view.UserId, Type: view.Type, Status: view.Status}, nil
}

type API struct {
	caseStore
	db            *sql.DB
	secret        []byte
	operators     map[int64]bool
	transfers     TransferLookup
	delay         time.Duration
	internalToken string
}

var ErrInternalAuth = errors.New("internal token required")

type claims struct {
	UserID string `json:"userId"`
	jwt.RegisteredClaims
}

func positiveID(value string) (int64, error) {
	id, err := strconv.ParseInt(value, 10, 64)
	if err != nil || id < 1 {
		return 0, errors.New("invalid ID")
	}
	return id, nil
}

func (a API) authenticate(header string) (int64, error) {
	scheme, raw, ok := strings.Cut(header, " ")
	if !ok || !strings.EqualFold(scheme, "Bearer") {
		return 0, errors.New("bearer token required")
	}
	var identity claims
	_, err := jwt.ParseWithClaims(raw, &identity, func(*jwt.Token) (any, error) {
		return a.secret, nil
	}, jwt.WithValidMethods([]string{"HS256"}), jwt.WithExpirationRequired())
	if err != nil {
		return 0, err
	}
	return positiveID(identity.UserID)
}

type signedHandler func(http.ResponseWriter, *http.Request, int64)

func (a API) authorized(operations bool, next signedHandler) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Cache-Control", "no-store")
		id, err := a.authenticate(r.Header.Get("Authorization"))
		if err != nil {
			fail(w, http.StatusUnauthorized, "Sign in to continue")
			return
		}
		if operations && !a.operators[id] {
			fail(w, http.StatusForbidden, "Operations access is required")
			return
		}
		next(w, r, id)
	}
}

func (a API) Routes() *http.ServeMux {
	mux := HealthRoutes(a.db)
	HandlerFromMux(contractAPI{a}, mux)
	return mux
}

func (a API) authorizeInternal(token string) error {
	if a.internalToken == "" || token != a.internalToken {
		return ErrInternalAuth
	}
	return nil
}

func (a API) ResearchContext(ctx context.Context, token string, id int64) (cases.Case, error) {
	if err := a.authorizeInternal(token); err != nil {
		return cases.Case{}, err
	}
	return a.ByIDInternal(ctx, id)
}

func (a API) ResearchDraft(ctx context.Context, token string, id int64, draft json.RawMessage) (cases.Case, error) {
	if err := a.authorizeInternal(token); err != nil {
		return cases.Case{}, err
	}
	if !completeDraft(draft) {
		return cases.Case{}, errors.New("draft object is required")
	}
	return a.SaveAiDraft(ctx, id, draft)
}

func respond(w http.ResponseWriter, status int, value any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(value)
}

func fail(w http.ResponseWriter, status int, message string) {
	respond(w, status, map[string]string{"message": message})
}

func storeError(w http.ResponseWriter, err error) {
	switch {
	case errors.Is(err, sql.ErrNoRows):
		fail(w, 404, "Dispute not found")
	case errors.Is(err, cases.ErrConflict):
		fail(w, 409, "Dispute command conflicts with the current case")
	default:
		slog.Error("Dispute database operation failed", "error", err)
		fail(w, 500, "Could not access disputes")
	}
}

func decode(w http.ResponseWriter, r *http.Request, value any) bool {
	d := json.NewDecoder(http.MaxBytesReader(w, r.Body, 16384))
	d.DisallowUnknownFields()
	return d.Decode(value) == nil && d.Decode(new(any)) == io.EOF
}

func completeDraft(draft json.RawMessage) bool {
	var body map[string]json.RawMessage
	if len(draft) == 0 || json.Unmarshal(draft, &body) != nil {
		return false
	}
	var summary, reason, action string
	var confidence float64
	return json.Unmarshal(body["summary"], &summary) == nil && summary != "" &&
		json.Unmarshal(body["reasonCode"], &reason) == nil && reason != "" &&
		json.Unmarshal(body["recommendedAction"], &action) == nil && action != "" &&
		json.Unmarshal(body["confidence"], &confidence) == nil
}

func validText(value string, limit int) bool {
	return value != "" && !strings.ContainsRune(value, 0) && utf8.RuneCountInString(value) <= limit
}

func (a API) file(w http.ResponseWriter, r *http.Request, customer int64) {
	var input struct {
		TransferID int64  `json:"transferId"`
		Reason     string `json:"reason"`
	}
	if !decode(w, r, &input) {
		fail(w, 400, "Invalid dispute request")
		return
	}
	reason := strings.TrimSpace(input.Reason)
	if input.TransferID < 1 || !validText(reason, 500) {
		fail(w, 400, "A transfer ID and a reason of 1 to 500 characters are required")
		return
	}
	ctx := r.Context()
	c, err := a.ByTransfer(ctx, customer, input.TransferID)
	if err == nil {
		respond(w, 200, c)
		return
	}
	if !errors.Is(err, sql.ErrNoRows) {
		storeError(w, err)
		return
	}
	if status := a.eligible(ctx, r.Header.Get("Authorization"), customer, input.TransferID); status != 200 {
		switch status {
		case 404:
			fail(w, status, "Transfer not found")
		case 422:
			fail(w, status, "Only completed transfers can be disputed")
		default:
			fail(w, status, "Could not verify transfer eligibility")
		}
		return
	}
	c, created, err := a.Create(ctx, customer, input.TransferID, reason, a.delay)
	if err != nil {
		storeError(w, err)
		return
	}
	if created {
		respond(w, 201, c)
		return
	}
	respond(w, 200, c)
}

func (a API) eligible(ctx context.Context, auth string, customer, id int64) int {
	if a.transfers == nil {
		return 502
	}
	transfer, err := a.transfers.Lookup(ctx, auth, id)
	if err != nil {
		switch status.Code(err) {
		case codes.NotFound, codes.PermissionDenied:
			return 404
		default:
			return 502
		}
	}
	if transfer.ID != id {
		return 502
	}
	if transfer.UserID != customer {
		return 404
	}
	if transfer.Type != "TRANSFER" || transfer.Status != "COMPLETED" {
		return 422
	}
	return 200
}

func (a API) customerList(w http.ResponseWriter, r *http.Request, customer int64) {
	items, err := a.CustomerCases(r.Context(), customer)
	if err != nil {
		storeError(w, err)
		return
	}
	respond(w, 200, items)
}

func (a API) customerRead(w http.ResponseWriter, r *http.Request, customer int64) {
	id, err := positiveID(r.PathValue("id"))
	if err != nil {
		storeError(w, sql.ErrNoRows)
		return
	}
	c, err := a.ByID(r.Context(), customer, id)
	if err != nil {
		storeError(w, err)
		return
	}
	respond(w, 200, c)
}

func (a API) queue(w http.ResponseWriter, r *http.Request, _ int64) {
	page := int64(1)
	if value := r.URL.Query().Get("page"); value != "" {
		var err error
		page, err = positiveID(value)
		if err != nil || page > 1_000_000 {
			fail(w, 400, "Invalid page")
			return
		}
	}
	const pageSize = 20
	items, err := a.OperationsQueue(r.Context(), pageSize+1, (page-1)*pageSize)
	if err != nil {
		storeError(w, err)
		return
	}
	hasMore := len(items) > pageSize
	if hasMore {
		items = items[:pageSize]
	}
	respond(w, 200, struct {
		Items   []cases.Case `json:"items"`
		HasMore bool         `json:"hasMore"`
	}{items, hasMore})
}

func (a API) resolveCase(w http.ResponseWriter, r *http.Request, operator int64) {
	id, err := positiveID(r.PathValue("id"))
	if err != nil {
		storeError(w, sql.ErrNoRows)
		return
	}
	var input struct {
		Resolution string `json:"resolution"`
	}
	if !decode(w, r, &input) {
		fail(w, 400, "Invalid resolution request")
		return
	}
	summary := strings.TrimSpace(input.Resolution)
	if !validText(summary, 2000) {
		fail(w, 400, "A resolution summary of 1 to 2000 characters is required")
		return
	}
	c, err := a.Resolve(r.Context(), id, operator, summary)
	if err != nil {
		storeError(w, err)
		return
	}
	respond(w, 200, c)
}
func HealthRoutes(db *sql.DB) *http.ServeMux {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /api/v1/health", func(w http.ResponseWriter, r *http.Request) {
		ctx, cancel := context.WithTimeout(r.Context(), time.Second)
		defer cancel()
		if err := db.PingContext(ctx); err != nil {
			w.WriteHeader(http.StatusServiceUnavailable)
			return
		}
		w.WriteHeader(http.StatusOK)
	})
	return mux
}

func OperatorIDs(value string) (map[int64]bool, error) {
	ids := make(map[int64]bool)
	if strings.TrimSpace(value) == "" {
		return ids, nil
	}
	for _, item := range strings.Split(value, ",") {
		id, err := positiveID(strings.TrimSpace(item))
		if err != nil {
			return nil, errors.New("DISPUTES_OPERATOR_IDS must contain comma-separated positive user IDs")
		}
		ids[id] = true
	}
	return ids, nil
}

func New(db *sql.DB, store caseStore, secret []byte, operators map[int64]bool, transfers TransferLookup, delay time.Duration) API {
	return NewWithInternal(db, store, secret, operators, transfers, delay, os.Getenv("DISPUTES_INTERNAL_TOKEN"))
}

func NewWithInternal(db *sql.DB, store caseStore, secret []byte, operators map[int64]bool, transfers TransferLookup, delay time.Duration, internalToken string) API {
	return API{caseStore: store, db: db, secret: secret, operators: operators, transfers: transfers, delay: delay, internalToken: internalToken}
}

func (a API) evidence(w http.ResponseWriter, r *http.Request, actor int64) {
	id, err := positiveID(r.PathValue("id"))
	if err != nil {
		storeError(w, sql.ErrNoRows)
		return
	}
	key := strings.TrimSpace(r.Header.Get("Idempotency-Key"))
	if !validText(key, 128) {
		fail(w, 400, "An Idempotency-Key of 1 to 128 characters is required")
		return
	}
	var input struct {
		Text string `json:"text"`
	}
	if !decode(w, r, &input) || !validText(strings.TrimSpace(input.Text), 2000) {
		fail(w, 400, "Evidence text of 1 to 2000 characters is required")
		return
	}
	var c cases.Case
	if requestID := r.PathValue("requestId"); requestID != "" {
		c, err = a.RespondToEvidence(r.Context(), id, actor, requestID, key, strings.TrimSpace(input.Text))
	} else {
		c, err = a.RequestEvidence(r.Context(), id, actor, key, strings.TrimSpace(input.Text))
	}
	if err != nil {
		storeError(w, err)
		return
	}
	respond(w, 200, c)
}
