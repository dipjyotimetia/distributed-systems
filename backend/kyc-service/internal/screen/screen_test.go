package screen

import "testing"

func TestOutcome(t *testing.T) {
	if Outcome("passport") != "APPROVED" || Outcome("REJECT") != "REJECTED" || Outcome(" review ") != "REVIEW" {
		t.Fatal(Outcome("passport"), Outcome("REJECT"), Outcome(" review "))
	}
}
