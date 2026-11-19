package integration

import (
	"context"
	"fmt"
	"net/url"

	"go.temporal.io/sdk/workflow"
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
	for _, path := range []string{
		"/api/users/check-username?username=" + url.QueryEscape(input.User),
		"/api/users/check-email?email=" + url.QueryEscape(input.email()),
	} {
		var result struct {
			Success   bool
			Available *bool
		}
		if err := call(ctx, a.Frontend, path, "", nil, 200, &result); err != nil {
			return err
		}
		if !result.Success || result.Available == nil || *result.Available != available {
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
	var profile struct {
		Success bool
		Data    struct {
			ID              int64
			Username, Email string
		}
	}
	if err := call(ctx, a.Frontend, "/api/users/profile", token, nil, 200, &profile); err != nil {
		return err
	}
	if !profile.Success || profile.Data.ID != id || profile.Data.Username != input.User || profile.Data.Email != input.email() {
		return fmt.Errorf("authenticated profile did not match registered identity")
	}
	return nil
}

func (a *Activities) AssertIdentityConflicts(ctx context.Context, input Input) error {
	for _, credentials := range []map[string]string{
		{"username": input.User, "email": "unused-" + input.email(), "password": a.Password},
		{"username": input.User + "unused", "email": input.email(), "password": a.Password},
	} {
		var conflict struct {
			Success bool
			Message string
		}
		if err := call(ctx, a.Frontend, "/api/users/register", "", credentials, 409, &conflict); err != nil {
			return err
		}
		if conflict.Success || conflict.Message == "" {
			return fmt.Errorf("duplicate registration did not return a conflict message")
		}
	}
	payload := map[string]string{"usernameOrEmail": input.User, "password": "invalid-" + a.Password}
	return call(ctx, a.Frontend, "/api/users/login", "", payload, 401, nil)
}
