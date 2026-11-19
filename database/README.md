# Database setup

Default Compose provisions PostgreSQL for identity, accounts, transactions
and disputes. It initializes the `user_service`, `accounts_service` and
`transactions_service` schemas on a fresh data directory using `init/`.
The `disputes-schema` Compose service applies the disputes bootstrap SQL
and provisions the `disputes` schema separately.

```bash
docker compose up -d postgres
psql -h localhost -p 5432 -U postgres -d banking_app
```

Use the credentials configured for your local environment. Run `\dn` in
psql to list schemas.

Accounts and transactions apply their Flyway migrations from their service
resources. The .NET user service bootstraps its table at startup in the
pre-provisioned schema. Use the explicit seed files and Kubernetes seed Job
for their intended development or simulation purposes. MongoDB stores
notification events in Kubernetes and the applicable integration scenarios.
