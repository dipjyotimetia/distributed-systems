package httpapi

import "net/http"

// contractAPI is the generated OpenAPI server. Method names that already exist
// on the store stay on this wrapper.
type contractAPI struct{ API }

func (c contractAPI) ListDisputes(w http.ResponseWriter, r *http.Request) {
	c.authorized(false, c.API.customerList)(w, r)
}

func (c contractAPI) FileDispute(w http.ResponseWriter, r *http.Request) {
	c.authorized(false, c.API.file)(w, r)
}

func (c contractAPI) GetDispute(w http.ResponseWriter, r *http.Request, _ Id) {
	c.authorized(false, c.API.customerRead)(w, r)
}

func (c contractAPI) RespondToEvidence(w http.ResponseWriter, r *http.Request, _ Id, _ RequestId, _ RespondToEvidenceParams) {
	c.authorized(false, c.API.evidence)(w, r)
}

func (c contractAPI) OperationsQueue(w http.ResponseWriter, r *http.Request, _ OperationsQueueParams) {
	c.authorized(true, c.API.queue)(w, r)
}

func (c contractAPI) RequestEvidence(w http.ResponseWriter, r *http.Request, _ Id, _ RequestEvidenceParams) {
	c.authorized(true, c.API.evidence)(w, r)
}

func (c contractAPI) ResolveDispute(w http.ResponseWriter, r *http.Request, _ Id) {
	c.authorized(true, c.API.resolveCase)(w, r)
}
