package main

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"sync"
)

func registerFinancialEndpoints(mux *http.ServeMux) {
	var mu sync.Mutex
	attempts := make(map[string][]int)
	fixture := func(method, path, response string, status int) {
		mux.HandleFunc(method+" "+path, func(w http.ResponseWriter, r *http.Request) {
			if method == "POST" {
				var body map[string]any
				if json.NewDecoder(http.MaxBytesReader(w, r.Body, 8192)).Decode(&body) != nil || len(body) == 0 {
					http.Error(w, "missing JSON payload", http.StatusBadRequest)
					return
				}
			}
			mu.Lock()
			attempts[path] = append(attempts[path], status)
			mu.Unlock()
			w.Header().Set("Content-Type", "application/json")
			w.WriteHeader(status)
			fmt.Fprint(w, response)
		})
	}
	fixture("POST", "/plaid/sandbox/public_token/create", `{"public_token":"synthetic-public"}`, 200)
	fixture("POST", "/plaid/item/public_token/exchange", `{"access_token":"synthetic-access"}`, 200)
	fixture("POST", "/plaid/accounts/balance/get", `{"accounts":[]}`, 200)
	fixture("GET", "/exchange", `{"base":"USD","rates":{"USD":1}}`, 200)
	fixture("GET", "/ratings", `{"error":"unavailable"}`, 503)
	mux.HandleFunc("GET /financial-evidence", func(w http.ResponseWriter, _ *http.Request) {
		mu.Lock()
		defer mu.Unlock()
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(attempts)
	})

	objects := make(map[string][]byte)
	mux.HandleFunc("PUT /statements-test/{key...}", func(w http.ResponseWriter, r *http.Request) {
		body, err := io.ReadAll(http.MaxBytesReader(w, r.Body, 32768))
		if err != nil || !json.Valid(body) || r.Header.Get("Content-Type") != "application/json" {
			http.Error(w, "invalid object", http.StatusBadRequest)
			return
		}
		key := r.PathValue("key")
		mu.Lock()
		defer mu.Unlock()
		if _, exists := objects[key]; exists {
			http.Error(w, "duplicate object", http.StatusConflict)
			return
		}
		objects[key] = body
		w.WriteHeader(http.StatusOK)
	})
	mux.HandleFunc("GET /statements-test/{key...}", func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		defer mu.Unlock()
		data, ok := objects[r.PathValue("key")]
		if !ok {
			http.NotFound(w, r)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write(data)
	})
}
