package main

import (
	"encoding/json"
	"net/http"
	"sync"
)

func registerPaymentEndpoints(mux *http.ServeMux) {
	var mu sync.Mutex
	attempts := make(map[string]map[string]int)
	record := func(w http.ResponseWriter, provider, amount string) {
		if amount == "" {
			http.Error(w, "missing amount", http.StatusBadRequest)
			return
		}
		mu.Lock()
		if attempts[provider] == nil {
			attempts[provider] = make(map[string]int)
		}
		attempts[provider][amount]++
		mu.Unlock()
		w.WriteHeader(http.StatusCreated)
	}
	mux.HandleFunc("POST /v1/payment_intents", func(w http.ResponseWriter, r *http.Request) {
		r.Body = http.MaxBytesReader(w, r.Body, 8192)
		if r.ParseForm() != nil || r.PostForm.Get("currency") != "usd" || r.PostForm.Get("payment_method_types[]") != "card" {
			http.Error(w, "invalid Stripe request", http.StatusBadRequest)
			return
		}
		record(w, "stripe", r.PostForm.Get("amount"))
	})
	mux.HandleFunc("POST /v2/checkout/orders", func(w http.ResponseWriter, r *http.Request) {
		var payload struct {
			Intent        string
			PurchaseUnits []struct {
				Amount struct {
					Currency string `json:"currency_code"`
					Value    string
				}
			} `json:"purchase_units"`
		}
		if json.NewDecoder(http.MaxBytesReader(w, r.Body, 8192)).Decode(&payload) != nil || payload.Intent != "CAPTURE" ||
			len(payload.PurchaseUnits) != 1 || payload.PurchaseUnits[0].Amount.Currency != "USD" {
			http.Error(w, "invalid PayPal request", http.StatusBadRequest)
			return
		}
		record(w, "paypal", payload.PurchaseUnits[0].Amount.Value)
	})
	mux.HandleFunc("GET /payment-evidence", func(w http.ResponseWriter, _ *http.Request) {
		mu.Lock()
		defer mu.Unlock()
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(attempts)
	})
}
