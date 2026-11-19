package integration

import (
	"context"
	"fmt"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func TestMutationTimeoutIsRedacted(t *testing.T) {
	requests := make(chan struct{}, 2)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		requests <- struct{}{}
		time.Sleep(100 * time.Millisecond)
		fmt.Fprint(w, "private")
	}))
	defer server.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 50*time.Millisecond)
	defer cancel()
	err := call(ctx, server.URL, "/transfer", "secret-token", map[string]string{"password": "private"}, 201, nil)
	if err == nil || err.Error() != "POST /transfer request failed" {
		t.Fatalf("unexpected error %v", err)
	}
	if count := len(requests); count != 1 {
		t.Fatalf("got %d requests, expected one", count)
	}
}

func TestRequestPreservesErrorBodies(t *testing.T) {
	const structured = `{"code":"INSUFFICIENT_FUNDS"}`
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPatch {
			t.Errorf("method = %s, want PATCH", r.Method)
		}
		if r.URL.Path == "/missing" {
			w.WriteHeader(http.StatusNotFound)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusConflict)
		fmt.Fprint(w, structured)
	}))
	defer server.Close()

	for _, check := range []struct {
		path   string
		status int
		body   string
	}{{"/missing", 404, ""}, {"/conflict", 409, structured}} {
		response, err := request(context.Background(), http.MethodPatch, server.URL, check.path, "", nil)
		if err != nil || response.Status != check.status || string(response.Body) != check.body {
			t.Fatalf("response on %s = %+v, %v", check.path, response, err)
		}
	}
}
