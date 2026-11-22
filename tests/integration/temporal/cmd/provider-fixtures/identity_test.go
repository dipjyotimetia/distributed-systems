package main

import (
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestIdentityEndpoints(t *testing.T) {
	mux := http.NewServeMux()
	registerIdentityEndpoints(mux)
	server := httptest.NewServer(mux)
	t.Cleanup(server.Close)

	socure, err := http.Post(server.URL+"/socure/api/3.0/EmailAuthScore", "application/json",
		strings.NewReader(`{"modules":["emailrisk"],"email":"a@b.com"}`))
	if err != nil {
		t.Fatal(err)
	}
	defer socure.Body.Close()
	if socure.StatusCode != http.StatusOK {
		t.Fatalf("socure status=%d", socure.StatusCode)
	}

	jumio, err := http.Post(server.URL+"/jumio/api/v4/initiate", "application/json",
		strings.NewReader(`{"customerInternalReference":"apex-user-1","userReference":"a@b.com"}`))
	if err != nil {
		t.Fatal(err)
	}
	defer jumio.Body.Close()
	if jumio.StatusCode != http.StatusOK {
		t.Fatalf("jumio status=%d", jumio.StatusCode)
	}

	hibp, err := http.Get(server.URL + "/hibp/api/v3/breachedaccount/a@b.com")
	if err != nil {
		t.Fatal(err)
	}
	defer hibp.Body.Close()
	if hibp.StatusCode != http.StatusNotFound {
		t.Fatalf("hibp status=%d want 404", hibp.StatusCode)
	}

	evidence, err := http.Get(server.URL + "/identity-evidence")
	if err != nil {
		t.Fatal(err)
	}
	defer evidence.Body.Close()
	body, _ := io.ReadAll(evidence.Body)
	if !strings.Contains(string(body), "/socure/api/3.0/EmailAuthScore") {
		t.Fatalf("evidence missing socure: %s", body)
	}
}

func TestFailClosedUnknownPath(t *testing.T) {
	mux := http.NewServeMux()
	registerIdentityEndpoints(mux)
	registerFinancialEndpoints(mux)
	server := httptest.NewServer(mux)
	t.Cleanup(server.Close)

	resp, err := http.Get(server.URL + "/no-such-provider/path")
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusNotFound {
		t.Fatalf("unknown path status=%d want 404", resp.StatusCode)
	}
}
