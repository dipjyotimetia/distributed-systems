package notify

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/url"
	"os"
	"strings"
	"time"
)

type TransactionInfo struct {
	TransactionID string
	Currency      string
	UserID        string
	Amount        float64
	Type          string
	Status        string
}

func (txn TransactionInfo) formattedAmount() string {
	if txn.Currency == "" {
		return fmt.Sprintf("$%.2f", txn.Amount)
	}
	return fmt.Sprintf("%s %.2f", txn.Currency, txn.Amount)
}

type Notifier struct {
	client          *http.Client
	sendgridKey     string
	sendgridURL     string
	twilioSID       string
	twilioBaseURL   string
	twilioToken     string
	slackWebhookURL string
}

func NewNotifier() *Notifier {
	return &Notifier{
		client:          &http.Client{Timeout: 5 * time.Second},
		sendgridURL:     envOr("SENDGRID_API_URL", "https://api.sendgrid.com/v3/mail/send"),
		twilioBaseURL:   strings.TrimRight(envOr("TWILIO_API_BASE_URL", "https://api.twilio.com"), "/"),
		sendgridKey:     envOr("SENDGRID_API_KEY", "SG.placeholder-key"),
		twilioSID:       envOr("TWILIO_ACCOUNT_SID", "ACplaceholder000000000000000000000"),
		twilioToken:     envOr("TWILIO_AUTH_TOKEN", "placeholder-token"),
		slackWebhookURL: envOr("SLACK_WEBHOOK_URL", "https://slack-webhook.example.com/mock-webhook"),
	}
}

var Channels = [...]string{"email", "sms", "slack"}

func (n *Notifier) Deliver(ctx context.Context, channel string, txn TransactionInfo) error {
	switch channel {
	case "email":
		return n.sendEmail(ctx, txn)
	case "sms":
		return n.sendSMS(ctx, txn)
	case "slack":
		return n.sendSlack(ctx, txn)
	default:
		return fmt.Errorf("unknown notification channel %q", channel)
	}
}

type ProviderStatus int

func (status ProviderStatus) Error() string {
	return fmt.Sprintf("provider returned HTTP %d", status)
}

func (status ProviderStatus) Retriable() bool {
	return status == 408 || status == 429 || status >= 500
}

func (n *Notifier) sendEmail(ctx context.Context, txn TransactionInfo) error {
	body := map[string]interface{}{
		"personalizations": []map[string]interface{}{
			{"to": []map[string]string{{"email": "customer@example.com"}}},
		},
		"from":    map[string]string{"email": "alerts@apexbanking.com"},
		"subject": fmt.Sprintf("Transaction %s: %s", txn.Status, txn.TransactionID),
		"content": []map[string]string{
			{
				"type":  "text/plain",
				"value": fmt.Sprintf("Transaction %s of %s (%s) is %s.", txn.TransactionID, txn.formattedAmount(), txn.Type, txn.Status),
			},
		},
	}
	payload, _ := json.Marshal(body)

	req, err := http.NewRequestWithContext(ctx, http.MethodPost, n.sendgridURL, bytes.NewReader(payload))
	if err != nil {
		return err
	}
	req.Header.Set("Authorization", "Bearer "+n.sendgridKey)
	req.Header.Set("Content-Type", "application/json")

	return n.send(req)
}

func (n *Notifier) sendSMS(ctx context.Context, txn TransactionInfo) error {
	endpoint := fmt.Sprintf("%s/2010-04-01/Accounts/%s/Messages.json", n.twilioBaseURL, n.twilioSID)

	form := url.Values{}
	form.Set("To", "+15551234567")
	form.Set("From", "+15559876543")
	form.Set("Body", fmt.Sprintf("Apex Banking: your %s of %s is %s (ref: %s)", txn.Type, txn.formattedAmount(), txn.Status, txn.TransactionID))

	req, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint, strings.NewReader(form.Encode()))
	if err != nil {
		return err
	}
	req.SetBasicAuth(n.twilioSID, n.twilioToken)
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")

	return n.send(req)
}

func (n *Notifier) sendSlack(ctx context.Context, txn TransactionInfo) error {
	body := map[string]string{
		"text":     fmt.Sprintf("[%s] Transaction %s: %s %s", txn.Status, txn.TransactionID, txn.formattedAmount(), txn.Type),
		"channel":  "#transaction-alerts",
		"username": "apex-banking-bot",
	}
	payload, _ := json.Marshal(body)

	req, err := http.NewRequestWithContext(ctx, http.MethodPost, n.slackWebhookURL, bytes.NewReader(payload))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")

	return n.send(req)
}

func (n *Notifier) send(req *http.Request) error {
	resp, err := n.client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return ProviderStatus(resp.StatusCode)
	}
	return nil
}

func envOr(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}
