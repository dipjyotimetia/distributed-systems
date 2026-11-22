package main

import (
	"encoding/json"
	"fmt"
	"net/http"
	"sync"
)

func registerIdentityEndpoints(mux *http.ServeMux) {
	var mu sync.Mutex
	attempts := make(map[string][]int)

	record := func(path string, status int) {
		mu.Lock()
		attempts[path] = append(attempts[path], status)
		mu.Unlock()
	}

	mux.HandleFunc("POST /socure/api/3.0/EmailAuthScore", func(w http.ResponseWriter, r *http.Request) {
		var body map[string]any
		if json.NewDecoder(http.MaxBytesReader(w, r.Body, 8192)).Decode(&body) != nil {
			http.Error(w, "invalid Socure payload", http.StatusBadRequest)
			return
		}
		record("/socure/api/3.0/EmailAuthScore", http.StatusOK)
		w.Header().Set("Content-Type", "application/json")
		fmt.Fprint(w, `{"referenceId":"synthetic-socure","emailRisk":{"score":0.1,"reason":"none"}}`)
	})

	mux.HandleFunc("POST /jumio/api/v4/initiate", func(w http.ResponseWriter, r *http.Request) {
		var body map[string]any
		if json.NewDecoder(http.MaxBytesReader(w, r.Body, 8192)).Decode(&body) != nil {
			http.Error(w, "invalid Jumio payload", http.StatusBadRequest)
			return
		}
		record("/jumio/api/v4/initiate", http.StatusOK)
		w.Header().Set("Content-Type", "application/json")
		fmt.Fprint(w, `{"timestamp":"2024-01-01T00:00:00.000Z","transactionReference":"synthetic-jumio"}`)
	})

	mux.HandleFunc("GET /hibp/api/v3/breachedaccount/{email}", func(w http.ResponseWriter, r *http.Request) {
		record("/hibp/api/v3/breachedaccount", http.StatusNotFound)
		// Empty breach list is represented as 404 by HIBP for unknown accounts.
		http.NotFound(w, r)
	})

	mux.HandleFunc("GET /identity-evidence", func(w http.ResponseWriter, _ *http.Request) {
		mu.Lock()
		defer mu.Unlock()
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(attempts)
	})
}
