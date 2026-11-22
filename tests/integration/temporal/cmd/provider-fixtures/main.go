package main

import (
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"os"
	"sync"
	"time"
)

type checks struct {
	mu               sync.Mutex
	Fraud            map[string]int
	ProviderStatuses map[string][]int
	Compliance       map[string]int
}

func main() {
	evidence := &checks{Fraud: make(map[string]int), Compliance: make(map[string]int), ProviderStatuses: make(map[string][]int)}
	mux := http.NewServeMux()
	mux.HandleFunc("GET /checks", func(w http.ResponseWriter, _ *http.Request) {
		evidence.mu.Lock()
		defer evidence.mu.Unlock()
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(evidence)
	})
	mux.HandleFunc("POST /searches", func(w http.ResponseWriter, r *http.Request) {
		var body struct {
			SearchTerm string `json:"search_term"`
		}
		if json.NewDecoder(http.MaxBytesReader(w, r.Body, 8192)).Decode(&body) != nil || body.SearchTerm == "" {
			http.Error(w, "invalid compliance payload", http.StatusBadRequest)
			return
		}
		evidence.mu.Lock()
		evidence.Compliance[body.SearchTerm]++
		evidence.mu.Unlock()
		w.Header().Set("Content-Type", "application/json")
		if body.SearchTerm == "SCREEN000001" {
			fmt.Fprint(w, `{"content":{"data":{"total_matches":1,"risk_level":"high"}}}`)
			return
		}
		fmt.Fprint(w, `{"content":{"data":{"total_matches":0,"risk_level":"low"}}}`)
	})
	registerDeliveryEndpoints(mux)
	registerAIEndpoints(mux)
	registerPaymentEndpoints(mux)
	registerFraudEndpoints(mux, evidence)
	registerFinancialEndpoints(mux)
	registerSettlementEndpoints(mux)
	registerIdentityEndpoints(mux)
	compliance := &http.Server{Addr: ":8081", Handler: mux, ReadHeaderTimeout: 5 * time.Second}
	log.Fatal(compliance.ListenAndServe())
}

func registerDeliveryEndpoints(mux *http.ServeMux) {
	var mu sync.Mutex
	type attempt struct {
		Message string
		Status  int
	}
	evidence := make(map[string][]attempt)
	failedProvider := os.Getenv("FAILED_NOTIFICATION_PROVIDER")
	record := func(w http.ResponseWriter, provider, message string) {
		if message == "" {
			http.Error(w, "missing notification message", http.StatusBadRequest)
			return
		}
		mu.Lock()
		status := http.StatusAccepted
		if provider == failedProvider && len(evidence[provider]) == 0 {
			status = http.StatusServiceUnavailable
		}
		evidence[provider] = append(evidence[provider], attempt{Message: message, Status: status})
		mu.Unlock()
		w.WriteHeader(status)
	}
	mux.HandleFunc("POST /slack", func(w http.ResponseWriter, r *http.Request) {
		var body struct{ Text string }
		if json.NewDecoder(http.MaxBytesReader(w, r.Body, 8192)).Decode(&body) != nil {
			http.Error(w, "invalid Slack payload", http.StatusBadRequest)
			return
		}
		record(w, "slack", body.Text)
	})
	mux.HandleFunc("POST /v3/mail/send", func(w http.ResponseWriter, r *http.Request) {
		var body struct{ Content []struct{ Value string } }
		if json.NewDecoder(http.MaxBytesReader(w, r.Body, 8192)).Decode(&body) != nil || len(body.Content) != 1 {
			http.Error(w, "invalid SendGrid payload", http.StatusBadRequest)
			return
		}
		record(w, "sendgrid", body.Content[0].Value)
	})
	mux.HandleFunc("POST /2010-04-01/Accounts/{account}/Messages.json", func(w http.ResponseWriter, r *http.Request) {
		r.Body = http.MaxBytesReader(w, r.Body, 8192)
		if r.ParseForm() != nil {
			http.Error(w, "invalid Twilio payload", http.StatusBadRequest)
			return
		}
		record(w, "twilio", r.PostForm.Get("Body"))
	})
	mux.HandleFunc("GET /deliveries", func(w http.ResponseWriter, _ *http.Request) {
		mu.Lock()
		defer mu.Unlock()
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(evidence)
	})
}
