package main

import (
	"bytes"
	"io"
	"net/http"
	"regexp"
	"sync"
)

func registerSettlementEndpoints(mux *http.ServeMux) {
	var mutex sync.Mutex
	objects := make(map[string][]byte)
	keyPattern := regexp.MustCompile(`^settlement/aud/[0-9]{4}-[0-9]{2}-[0-9]{2}\.dat$`)

	mux.HandleFunc("PUT /settlements-test/{key...}", func(w http.ResponseWriter, r *http.Request) {
		key := r.PathValue("key")
		body, err := io.ReadAll(http.MaxBytesReader(w, r.Body, 700000))
		if err != nil || r.Header.Get("If-None-Match") != "*" || !keyPattern.MatchString(key) || !bytes.HasPrefix(body, []byte("01H")) {
			http.Error(w, "invalid settlement object", http.StatusBadRequest)
			return
		}
		mutex.Lock()
		defer mutex.Unlock()
		if _, ok := objects[key]; ok {
			w.Header().Set("Content-Type", "application/xml")
			w.WriteHeader(http.StatusPreconditionFailed)
			_, _ = io.WriteString(w, "<Error><Code>PreconditionFailed</Code></Error>")
			return
		}
		objects[key] = body
		w.WriteHeader(http.StatusOK)
	})

	mux.HandleFunc("GET /settlements-test/{key...}", func(w http.ResponseWriter, r *http.Request) {
		mutex.Lock()
		data, ok := objects[r.PathValue("key")]
		mutex.Unlock()
		if !ok {
			w.Header().Set("Content-Type", "application/xml")
			w.WriteHeader(http.StatusNotFound)
			_, _ = io.WriteString(w, "<Error><Code>NoSuchKey</Code></Error>")
			return
		}
		w.Header().Set("Content-Type", "application/octet-stream")
		_, _ = w.Write(data)
	})
}
