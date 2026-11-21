package integration

import (
	"context"
	"fmt"
	"net/http"
	"slices"

	"go.temporal.io/sdk/workflow"
	banking "microsvc/integration/temporal/gen"
)

var aiProviders = []string{"anthropic", "openai", "gemini", "xai", "openrouter"}

func AIJourney(ctx workflow.Context, input Input) ([]BoundaryEvidence, error) {
	var a *Activities
	reads := workflow.WithActivityOptions(ctx, evidenceOptions())
	writes := workflow.WithActivityOptions(ctx, mutationOptions())
	if err := workflow.ExecuteActivity(reads, a.VerifyAIProviders).Get(ctx, nil); err != nil {
		return nil, err
	}
	var evidence []BoundaryEvidence
	for _, mode := range []string{"success", "partial", "failure"} {
		var result BoundaryEvidence
		if err := workflow.ExecuteActivity(writes, a.VerifyAIChat, input, mode).Get(ctx, &result); err != nil {
			return nil, err
		}
		evidence = append(evidence, result)
	}
	return evidence, nil
}

func (a *Activities) VerifyAIProviders(ctx context.Context) error {
	client, err := a.api("")
	if err != nil {
		return err
	}
	response, err := client.ListAiProvidersWithResponse(ctx)
	if err != nil {
		return err
	}
	if err = expectStatus("GET /ai/chat", response.StatusCode(), http.StatusOK); err != nil {
		return err
	}
	if response.JSON200 == nil || len(*response.JSON200) != len(aiProviders) {
		found := 0
		if response.JSON200 != nil {
			found = len(*response.JSON200)
		}
		return fmt.Errorf("expected five AI providers, discovered %d", found)
	}
	seen := make(map[string]bool)
	for _, provider := range *response.JSON200 {
		if !slices.Contains(aiProviders, provider.Provider) || seen[provider.Provider] || provider.Name == "" || provider.Model == "" {
			return fmt.Errorf("invalid AI provider metadata")
		}
		seen[provider.Provider] = true
	}
	return nil
}

func (a *Activities) VerifyAIChat(ctx context.Context, input Input, mode string) (BoundaryEvidence, error) {
	client, err := a.api("")
	if err != nil {
		return BoundaryEvidence{}, err
	}
	message := "journey-" + mode + "-" + input.User
	locale := "fr-CA"
	response, err := client.AiChatWithResponse(ctx, banking.ChatRequest{Message: message, Locale: &locale})
	if err != nil {
		return BoundaryEvidence{}, err
	}
	if err = expectStatus("POST /ai/chat", response.StatusCode(), http.StatusOK); err != nil {
		return BoundaryEvidence{}, err
	}
	if response.JSON200 == nil || len(response.JSON200.Results) != len(aiProviders) {
		return BoundaryEvidence{}, fmt.Errorf("expected five AI results")
	}
	var attempts map[string][]int
	if err := call(ctx, a.Deliveries, "/ai-evidence/"+message, "", nil, 200, &attempts); err != nil {
		return BoundaryEvidence{}, err
	}
	for _, provider := range aiProviders {
		status := 200
		if mode == "failure" || (mode == "partial" && provider == "gemini") {
			status = 503
		}
		if !slices.Equal(attempts[provider], []int{status}) {
			return BoundaryEvidence{}, fmt.Errorf("expected one %s request carrying locale fr-CA and returning %d (found %v)", provider, status, attempts[provider])
		}
	}
	seen := make(map[string]bool)
	for _, result := range response.JSON200.Results {
		if !slices.Contains(aiProviders, result.Provider) || seen[result.Provider] {
			return BoundaryEvidence{}, fmt.Errorf("unexpected or repeated AI provider")
		}
		seen[result.Provider] = true
		fails := mode == "failure" || (mode == "partial" && result.Provider == "gemini")
		if fails && (result.Error == nil || text(result.Message) != "") {
			return BoundaryEvidence{}, fmt.Errorf("expected provider failure for %s", result.Provider)
		}
		if !fails && (result.Error != nil || text(result.Message) != result.Provider+" response") {
			return BoundaryEvidence{}, fmt.Errorf("expected provider success for %s", result.Provider)
		}
	}
	return BoundaryEvidence{"AI provider fan-out", "five requests carrying locale fr-CA and " + mode + " results",
		"five matching requests and provider results confirmed"}, nil
}
