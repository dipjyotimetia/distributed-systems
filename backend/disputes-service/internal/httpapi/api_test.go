package httpapi

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	transactionsv1 "github.com/dipjyotimetia/distributed-systems/backend/disputes-service/gen/transactions/v1"
	"github.com/golang-jwt/jwt/v5"
	"google.golang.org/grpc"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
)

var testKey = []byte("synthetic-test-signing-key-for-disputes")

func token(t *testing.T, method jwt.SigningMethod, identity string, expiry *jwt.NumericDate, key []byte) string {
	t.Helper()
	raw, err := jwt.NewWithClaims(method, claims{UserID: identity, RegisteredClaims: jwt.RegisteredClaims{
		ExpiresAt: expiry,
	}}).SignedString(key)
	if err != nil {
		t.Fatal(err)
	}
	return "Bearer " + raw
}

func TestResearchDraftRequiresFields(t *testing.T) {
	api := API{internalToken: "token"}
	_, err := api.ResearchDraft(context.Background(), "token", 1, json.RawMessage(`{}`))
	if err == nil || err.Error() != "draft object is required" {
		t.Fatalf("empty draft: %v", err)
	}
}

func signed(t *testing.T, id string) string {
	return token(t, jwt.SigningMethodHS256, id, jwt.NewNumericDate(time.Now().Add(time.Hour)), testKey)
}

func call(handler http.Handler, method, path, auth, body string) *httptest.ResponseRecorder {
	request := httptest.NewRequest(method, path, strings.NewReader(body))
	request.Header.Set("Authorization", auth)
	request.Header.Set("X-User-Id", "8")
	response := httptest.NewRecorder()
	handler.ServeHTTP(response, request)
	return response
}

func expect(t *testing.T, response *httptest.ResponseRecorder, status int) {
	t.Helper()
	if response.Code != status {
		t.Fatalf("status %d, expected %d, body %s", response.Code, status, response.Body)
	}
}

func TestAuthorization(t *testing.T) {
	a := API{secret: testKey}
	routes := a.Routes()
	future := jwt.NewNumericDate(time.Now().Add(time.Hour))
	for _, header := range []string{
		"", "Bearer", "Basic test", "Bearer invalid",
		token(t, jwt.SigningMethodHS384, "8", future, testKey),
		token(t, jwt.SigningMethodHS256, "8", future, []byte("different-signing-key")),
		token(t, jwt.SigningMethodHS256, "8", nil, testKey),
		token(t, jwt.SigningMethodHS256, "8", jwt.NewNumericDate(time.Now().Add(-time.Hour)), testKey),
		token(t, jwt.SigningMethodHS256, "", future, testKey),
		token(t, jwt.SigningMethodHS256, "0", future, testKey),
	} {
		expect(t, call(routes, "GET", "/api/v1/disputes", header, ""), 401)
	}
	expect(t, call(routes, "GET", "/api/v1/operations/disputes", signed(t, "8"), ""), 403)
	expect(t, call(routes, "POST", "/api/v1/operations/disputes/1/resolve", signed(t, "8"), "{}"), 403)
	ids, err := OperatorIDs("8, 12")
	if err != nil || !ids[8] || !ids[12] || ids[1] {
		t.Fatalf("operator allowlist %v, %v", ids, err)
	}
	if ids, err := OperatorIDs(""); err != nil || len(ids) != 0 {
		t.Fatal("empty allowlist should deny everyone")
	}
	if _, err := OperatorIDs("8, x"); err == nil {
		t.Fatal("invalid allowlist accepted")
	}
}

type scriptedLookup struct {
	view TransferView
	err  error
	auth string
}

func (s scriptedLookup) Lookup(_ context.Context, authorization string, _ int64) (TransferView, error) {
	if s.auth != "" && authorization != s.auth {
		return TransferView{}, s.err
	}
	return s.view, s.err
}

type stubTransactions struct {
	view *transactionsv1.TransactionView
	err  error
}

func (s stubTransactions) GetTransaction(context.Context, *transactionsv1.GetTransactionRequest, ...grpc.CallOption) (*transactionsv1.TransactionView, error) {
	return s.view, s.err
}

func TestTransactionLookup(t *testing.T) {
	query := Query{Client: stubTransactions{err: status.Error(codes.Unavailable, "down")}}
	if _, err := query.Lookup(context.Background(), "Bearer token", 4); err == nil {
		t.Fatal("lookup error was ignored")
	}
	query.Client = stubTransactions{view: &transactionsv1.TransactionView{Id: 4, UserId: 7, Type: "TRANSFER", Status: "COMPLETED"}}
	view, err := query.Lookup(context.Background(), "Bearer token", 4)
	if err != nil || view.ID != 4 || view.UserID != 7 || view.Type != "TRANSFER" || view.Status != "COMPLETED" {
		t.Fatalf("lookup view %+v, %v", view, err)
	}
}

func TestTransferEligibility(t *testing.T) {
	completed := TransferView{ID: 17, UserID: 7, Type: "TRANSFER", Status: "COMPLETED"}
	for _, tc := range []struct {
		view TransferView
		err  error
		want int
	}{
		{completed, nil, 200},
		{TransferView{ID: 17, UserID: 8, Type: "TRANSFER", Status: "COMPLETED"}, nil, 404},
		{TransferView{ID: 18, UserID: 7, Type: "TRANSFER", Status: "COMPLETED"}, nil, 502},
		{TransferView{ID: 17, UserID: 7, Type: "TRANSFER", Status: "PENDING"}, nil, 422},
		{TransferView{ID: 17, UserID: 7, Type: "DEPOSIT", Status: "COMPLETED"}, nil, 422},
		{TransferView{}, status.Error(codes.NotFound, "missing"), 404},
		{TransferView{}, status.Error(codes.PermissionDenied, "denied"), 404},
		{TransferView{}, status.Error(codes.Internal, "down"), 502},
	} {
		a := API{transfers: scriptedLookup{view: tc.view, err: tc.err, auth: "Bearer test"}}
		if got := a.eligible(context.Background(), "Bearer test", 7, 17); got != tc.want {
			t.Errorf("%+v %v: got %d, want %d", tc.view, tc.err, got, tc.want)
		}
	}
}
