package transactions

import "encoding/json"

// modeledRejection accepts an insufficient-funds object or a string field map.
// Any other JSON body stays out of JSON400 so callers can tell validation maps,
// confirmed insufficient funds, and unexpected payloads apart.
func modeledRejection(body []byte) (*Rejection, bool) {
	var object map[string]json.RawMessage
	if err := json.Unmarshal(body, &object); err != nil || len(object) == 0 {
		return nil, false
	}
	for _, value := range object {
		var text string
		if err := json.Unmarshal(value, &text); err != nil {
			return nil, false
		}
	}
	code, hasCode := object["code"]
	message, hasMessage := object["message"]
	if hasCode && hasMessage && len(object) == 2 {
		var codeText, messageText string
		if json.Unmarshal(code, &codeText) == nil && json.Unmarshal(message, &messageText) == nil &&
			codeText == string(INSUFFICIENTFUNDS) && messageText != "" {
			return rejection(body)
		}
	}
	return rejection(body)
}

func rejection(body []byte) (*Rejection, bool) {
	var parsed Rejection
	if err := json.Unmarshal(body, &parsed); err != nil {
		return nil, false
	}
	return &parsed, true
}
