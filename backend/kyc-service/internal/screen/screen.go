package screen

import "strings"

// Outcome is the fixture screener result for a document reference.
func Outcome(reference string) string {
	switch strings.ToUpper(strings.TrimSpace(reference)) {
	case "REJECT":
		return "REJECTED"
	case "REVIEW":
		return "REVIEW"
	default:
		return "APPROVED"
	}
}
