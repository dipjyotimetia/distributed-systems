package main

import (
	"encoding/json"
	"net/http"
	"regexp"
	"strings"
	"sync"
)

var aiMessage = regexp.MustCompile(`journey-(success|partial|failure)-bank[a-z0-9]+`)

func registerAIEndpoints(mux *http.ServeMux) {
	var mu sync.Mutex
	evidence := make(map[string]map[string][]int)
	mux.HandleFunc("POST /ai/{provider}", func(w http.ResponseWriter, r *http.Request) {
		var request struct {
			Messages []struct{ Role, Content string }
			Contents []struct {
				Role  string
				Parts []struct{ Text string }
			}
		}
		if json.NewDecoder(http.MaxBytesReader(w, r.Body, 32768)).Decode(&request) != nil {
			http.Error(w, "invalid request", http.StatusBadRequest)
			return
		}
		var userContent string
		for _, message := range request.Messages {
			if message.Role == "user" {
				userContent = message.Content
			}
		}
		if len(request.Contents) == 1 && request.Contents[0].Role == "user" && len(request.Contents[0].Parts) == 1 {
			userContent = request.Contents[0].Parts[0].Text
		}
		match := aiMessage.FindStringSubmatch(userContent)
		if len(match) != 2 || !strings.Contains(userContent, "User locale: fr-CA.") {
			http.Error(w, "missing integration message or locale", http.StatusBadRequest)
			return
		}
		provider := r.PathValue("provider")
		status := http.StatusOK
		if match[1] == "failure" || (match[1] == "partial" && provider == "gemini") {
			status = http.StatusServiceUnavailable
		}
		mu.Lock()
		if evidence[match[0]] == nil {
			evidence[match[0]] = make(map[string][]int)
		}
		evidence[match[0]][provider] = append(evidence[match[0]][provider], status)
		mu.Unlock()
		type object = map[string]any
		text := provider + " response"
		var response object
		switch provider {
		case "anthropic":
			response = object{"content": []object{{"text": text}}}
		case "gemini":
			response = object{"candidates": []object{{"content": object{"parts": []object{{"text": text}}}}}}
		default:
			response = object{"choices": []object{{"message": object{"content": text}}}}
		}
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(status)
		_ = json.NewEncoder(w).Encode(response)
	})
	mux.HandleFunc("GET /ai-evidence/{message}", func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		defer mu.Unlock()
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(evidence[r.PathValue("message")])
	})
}
