package httpapi

import (
	"context"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/golang-jwt/jwt/v5"
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
		expect(t, call(routes, "GET", "/api/disputes", header, ""), 401)
	}
	expect(t, call(routes, "GET", "/api/operations/disputes", signed(t, "8"), ""), 403)
	expect(t, call(routes, "POST", "/api/operations/disputes/1/resolve", signed(t, "8"), "{}"), 403)
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

func TestTransferEligibility(t *testing.T) {
	for _, tc := range []struct {
		code int
		body string
		want int
	}{
		{200, `{"id":17,"userId":7,"type":"TRANSFER","status":"COMPLETED"}`, 200},
		{200, `{"id":17,"userId":8,"type":"TRANSFER","status":"COMPLETED"}`, 404},
		{200, `{"id":18,"userId":7,"type":"TRANSFER","status":"COMPLETED"}`, 502},
		{200, `{"id":17,"userId":7,"type":"TRANSFER","status":"PENDING"}`, 422},
		{200, `{"id":17,"userId":7,"type":"DEPOSIT","status":"COMPLETED"}`, 422},
		{200, `bad response`, 502}, {404, "", 404}, {403, "", 404}, {500, "", 502},
	} {
		upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if r.Method != "GET" || r.URL.Path != "/api/transactions/17" || r.Header.Get("Authorization") != "Bearer test" {
				t.Error("incorrect transaction read")
			}
			w.WriteHeader(tc.code)
			fmt.Fprint(w, tc.body)
		}))
		a := API{transactionsURL: upstream.URL, client: upstream.Client()}
		if got := a.eligible(context.Background(), "Bearer test", 7, 17); got != tc.want {
			t.Errorf("upstream %d %s: got %d, want %d", tc.code, tc.body, got, tc.want)
		}
		upstream.Close()
	}
}
