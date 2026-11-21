package integration

import (
	"context"
	"fmt"
	"net/http"

	"go.temporal.io/sdk/workflow"
	banking "microsvc/integration/temporal/gen"
)

func IdentityJourney(ctx workflow.Context, input Input) ([]BoundaryEvidence, error) {
	var a *Activities
	ctx = workflow.WithActivityOptions(ctx, mutationOptions())
	for _, identity := range []Input{input, {User: input.User + "b"}} {
		if err := workflow.ExecuteActivity(ctx, a.AssertAvailability, identity, true).Get(ctx, nil); err != nil {
			return nil, err
		}
		var id int64
		if err := workflow.ExecuteActivity(ctx, a.Register, identity).Get(ctx, &id); err != nil {
			return nil, err
		}
		if err := workflow.ExecuteActivity(ctx, a.AssertAvailability, identity, false).Get(ctx, nil); err != nil {
			return nil, err
		}
		if err := workflow.ExecuteActivity(ctx, a.AssertLoginAndProfile, identity, id).Get(ctx, nil); err != nil {
			return nil, err
		}
	}
	if err := workflow.ExecuteActivity(ctx, a.AssertIdentityConflicts, input).Get(ctx, nil); err != nil {
		return nil, err
	}
	return []BoundaryEvidence{{"Frontend identity", "unique registrations, scoped profiles, conflicts and invalid credentials",
		"both users registered, availability changed, profiles matched each authenticated identity, duplicate registrations returned 409 and invalid login returned 401"}}, nil
}

func (a *Activities) AssertAvailability(ctx context.Context, input Input, available bool) error {
	client, err := a.api("")
	if err != nil {
		return err
	}
	username, err := client.CheckUsernameWithResponse(ctx, &banking.CheckUsernameParams{Username: input.User})
	if err != nil {
		return err
	}
	email, err := client.CheckEmailWithResponse(ctx, &banking.CheckEmailParams{Email: input.email()})
	if err != nil {
		return err
	}
	for _, result := range []*banking.Availability{username.JSON200, email.JSON200} {
		if username.StatusCode() != http.StatusOK || email.StatusCode() != http.StatusOK || result == nil || !result.Success || result.Available != available {
			return fmt.Errorf("expected availability %t", available)
		}
	}
	return nil
}

func (input Input) email() string { return input.User + "@example.test" }

func (a *Activities) AssertLoginAndProfile(ctx context.Context, input Input, id int64) error {
	token, err := a.login(ctx, input)
	if err != nil {
		return err
	}
	client, err := a.api(token)
	if err != nil {
		return err
	}
	profile, err := client.GetProfileWithResponse(ctx)
	if err != nil {
		return err
	}
	if err = expectStatus("GET /users/profile", profile.StatusCode(), http.StatusOK); err != nil {
		return err
	}
	if profile.JSON200 == nil || !profile.JSON200.Success || profile.JSON200.Data.Id != id || profile.JSON200.Data.Username != input.User || profile.JSON200.Data.Email != input.email() {
		return fmt.Errorf("authenticated profile did not match registered identity")
	}
	return nil
}

func (a *Activities) AssertIdentityConflicts(ctx context.Context, input Input) error {
	client, err := a.api("")
	if err != nil {
		return err
	}
	for _, credentials := range []banking.RegisterRequest{
		{Username: input.User, Email: "unused-" + input.email(), Password: a.Password},
		{Username: input.User + "unused", Email: input.email(), Password: a.Password},
	} {
		conflict, err := client.RegisterUserWithResponse(ctx, credentials)
		if err != nil {
			return err
		}
		if conflict.StatusCode() != http.StatusConflict || conflict.JSON409 == nil || conflict.JSON409.Success || conflict.JSON409.Message == "" {
			return fmt.Errorf("duplicate registration did not return a conflict message")
		}
	}
	login, err := client.LoginUserWithResponse(ctx, banking.LoginRequest{UsernameOrEmail: input.User, Password: "invalid-" + a.Password})
	if err != nil {
		return err
	}
	return expectStatus("POST /users/login", login.StatusCode(), http.StatusUnauthorized)
}
