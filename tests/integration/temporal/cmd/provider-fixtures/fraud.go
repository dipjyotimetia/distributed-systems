package main

import (
	"encoding/json"
	"fmt"
	"net/http"
	"strings"
)

func registerFraudEndpoints(mux *http.ServeMux, evidence *checks) {
	failProviders := false
	record := func(w http.ResponseWriter, provider, identity string) {
		if identity == "" {
			http.Error(w, "missing fraud identity", http.StatusBadRequest)
			return
		}
		evidence.mu.Lock()
		status := http.StatusOK
		if failProviders {
			status = http.StatusServiceUnavailable
		}
		evidence.ProviderStatuses[provider] = append(evidence.ProviderStatuses[provider], status)
		if provider == "sift" {
			evidence.Fraud[identity]++
		}
		evidence.mu.Unlock()
		w.WriteHeader(status)
	}
	mux.HandleFunc("POST /radar", func(w http.ResponseWriter, r *http.Request) {
		r.Body = http.MaxBytesReader(w, r.Body, 8192)
		if r.ParseForm() != nil || r.PostForm.Get("value_list") == "" {
			http.Error(w, "invalid Radar request", http.StatusBadRequest)
			return
		}
		record(w, "radar", r.PostForm.Get("value"))
	})
	mux.HandleFunc("POST /sift", func(w http.ResponseWriter, r *http.Request) {
		var body struct {
			Amount        int64  `json:"$amount"`
			UserID        string `json:"$user_id"`
			TransactionID string `json:"$transaction_id"`
			Category      string `json:"$mcc"`
		}
		if json.NewDecoder(http.MaxBytesReader(w, r.Body, 8192)).Decode(&body) != nil ||
			body.UserID == "" || body.Category != "5812" || body.Amount <= 0 {
			http.Error(w, "invalid Sift request", http.StatusBadRequest)
			return
		}
		accountID, _, ok := strings.Cut(body.TransactionID, "-")
		if !ok || accountID == "" {
			http.Error(w, "missing account ID", http.StatusBadRequest)
			return
		}
		record(w, "sift", fmt.Sprintf("%s/%s/TRANSFER/%.2f", body.UserID, accountID, float64(body.Amount)/1e6))
	})
	mux.HandleFunc("POST /maxmind", func(w http.ResponseWriter, r *http.Request) {
		var body struct {
			Event struct {
				TransactionID string `json:"transaction_id"`
			}
		}
		if json.NewDecoder(http.MaxBytesReader(w, r.Body, 8192)).Decode(&body) != nil {
			http.Error(w, "invalid MaxMind request", http.StatusBadRequest)
			return
		}
		record(w, "maxmind", body.Event.TransactionID)
	})
	mux.HandleFunc("PUT /fraud-failure", func(w http.ResponseWriter, _ *http.Request) {
		evidence.mu.Lock()
		failProviders = true
		evidence.mu.Unlock()
		w.WriteHeader(http.StatusNoContent)
	})
}
