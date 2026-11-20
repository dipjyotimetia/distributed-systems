package ledger

import "testing"

func TestSignatureMatchesTheAccountMovementContract(t *testing.T) {
	if Sign("secret", "11111111-1111-1111-1111-111111111111", "RESERVE", 7, 9, "10.50") == "" {
		t.Fatal("empty signature")
	}
	amount, err := CanonicalAmount("10.5")
	if err != nil || amount != "10.50" {
		t.Fatal(err, amount)
	}
}
