# Registration and seeded data

Registration creates an identity. The .NET registration request still accepts
`seedAccountData`, but the registration handler does not use it to create
accounts or transactions. After signing in, create an account from the UI.

For repeatable test data, use the isolated
[banking integration scenarios](../tests/integration/README.md). They create
their own identities and accounts as part of the test.
