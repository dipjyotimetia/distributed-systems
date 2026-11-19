# Banking journey coverage

This matrix tracks the frontend API contracts and their test evidence.
Bracketed catch-all route handlers expose multiple operations.

| Method and frontend route | Expected outcome | Test layer | Evidence |
| --- | --- | --- | --- |
| GET `/api/accounts` | Owned accounts, an array by default and a page on request | Temporal, service integration | `LedgerJourney.VerifyAccounts` verifies the owned array and page metadata |
| POST `/api/accounts` | Create an account with the requested balance and currency | Temporal, service integration | `Journey.CreateAccount` and `AccountServiceIntegrationTest` |
| GET `/api/accounts/[id]` | Return an owned account | Temporal | `LedgerJourney.VerifyAccounts` compares the detail and checks an empty cross-user 404 |
| PUT `/api/accounts/[id]` | Update account type or status | Temporal, service integration | `VerifyAccountTypeUpdate` and `updateAccountTypePreservesBalanceAndOwnership` cover type changes; status uses the same lifecycle rules as PATCH |
| DELETE `/api/accounts/[id]` | Close an owned zero-balance account, return 204 on repeat | Real stack, service integration | `VerifyAccountLifecycle` checks repeated closure and retained history. `MovementReservationsPostgresTest` races closure against an incoming movement |
| PATCH `/api/accounts/[id]/status` | Freeze, unfreeze, or close subject to lifecycle rules | Real stack, service integration | `VerifyAccountLifecycle` and `MovementReservationsPostgresTest` check freeze, unfreeze, and movement rejection |
| GET `/api/accounts/[id]/balance` | Return the owned balance and currency | Temporal, service integration | `Journey.Verify` checks exact balances and `LedgerJourney.VerifyFinancialEnrichment` checks provider requests |
| POST `/api/accounts/[id]/export-statement` | Generate an inline or stored statement | Temporal | `LedgerJourney.VerifyAccounts` verifies inline JSON and `StatementJourney.VerifyStoredStatement` reads back the S3-compatible object |
| GET `/api/accounts/[id]/transactions` | Return the ledger after an account ownership check | Temporal, service integration | `LedgerJourney.VerifyLedger` checks ordered rows and a cross-user 404 |
| GET `/api/accounts/[id]/transactions/summary` | Return counts after an account ownership check | Temporal, service integration | `LedgerJourney.VerifyLedger` checks counts and a cross-user 404 |
| GET `/api/accounts/search` | Page matching owned accounts | Temporal, service integration | `LedgerJourney.VerifyAccounts` verifies a filtered page and ownership |
| GET `/api/accounts/summary` | Count owned accounts by type and active status and sum balances | Temporal, service integration | `LedgerJourney.VerifyAccounts` verifies scoped count, type and exact balance |
| GET `/api/ai/chat` | Discover available providers | Temporal | `AIJourney.VerifyAIProviders` checks discovery of all five providers |
| POST `/api/ai/chat` | Aggregate five providers, preserve locale, and handle provider failures | Temporal | `AIJourney.VerifyAIChat` checks locale, five successes, one failure and five failures against fixture responses |
| GET `/api/healthz` | Return frontend health | Temporal startup | `testJourney` checks HTTP 200 from Next.js |
| GET `/api/transactions` | Return the owned ledger, with optional filters and paging | Temporal, service integration | `LedgerJourney.VerifyLedger` checks the full list and a filtered page |
| POST `/api/transactions` | Dispatch a supported generic transaction to its operation | Temporal | `LedgerJourney.MoveFunds` dispatches a deposit and verifies the resulting ledger |
| GET `/api/transactions/[id]` | Read an owned transaction | Temporal, service integration | `LedgerJourney.VerifyLedger` compares exact detail and checks a cross-user 404 |
| PATCH `/api/transactions/[id]/cancel` | Cancel an owned pending record once, return 409 for settled records | Temporal, service integration | `LedgerJourney` checks settled conflict, pending cancellation, retry and foreign ownership through Next.js and PostgreSQL |
| POST `/api/transactions/create` | Dispatch a supported generic transaction to its operation | Temporal | `LedgerJourney.MoveFunds` dispatches a withdrawal and verifies the resulting ledger |
| POST `/api/transactions/deposit` | Complete a deposit and update the ledger | Temporal, service integration | `LedgerJourney.MoveFunds` checks the completed deposit and exact ledger amount |
| POST `/api/transactions/withdraw` | Complete a withdrawal or reject insufficient funds | Temporal, service integration | `LedgerJourney.MoveFunds` checks the withdrawal. `RejectWithdrawal` checks insufficient funds |
| POST `/api/transactions/transfer` | Settle the transfer once and return 400 with `INSUFFICIENT_FUNDS` for a subsequent rejected transfer | Temporal | `Journey.SubmitTransfer`, `Journey.Verify`, and `Journey.Reject` check the completed transfer and rejected attempts |
| GET `/api/transactions/recent` | Return the newest owned ledger entries | Temporal, service integration | `LedgerJourney.VerifyLedger` verifies newest-first returned entries |
| GET `/api/transactions/search` | Page matching owned ledger entries | Temporal, service integration | `LedgerJourney.VerifyLedger` checks a filtered deposit page |
| GET `/api/transactions/summary` | Count owned ledger entries by type and status | Temporal, service integration | `LedgerJourney.VerifyLedger` checks totals by type and completed status |
| GET `/api/users/check-email` | Return email availability | Temporal | `IdentityJourney.AssertAvailability` checks availability before and after registration |
| GET `/api/users/check-username` | Return username availability | Temporal | `IdentityJourney.AssertAvailability` checks availability before and after registration |
| POST `/api/users/login` | Authenticate valid credentials and reject invalid credentials | Temporal, Playwright | `IdentityJourney` checks accepted and rejected credentials. `real-backend.spec.ts` checks cookie-backed login |
| GET `/api/users/profile` | Return the authenticated profile | Temporal, Playwright | `IdentityJourney.AssertLoginAndProfile` verifies different identities and `real-backend.spec.ts` verifies session restoration |
| POST `/api/users/register` | Register synthetic identity and reject duplicates | Temporal | `IdentityJourney.Register` and `AssertIdentityConflicts` check registration and conflicts on username and email |
| POST `/api/users/logout` | Clear the browser session cookie | Playwright | `real-backend.spec.ts` checks logout |
| GET and POST `/api/disputes/[[...casePath]]` | File and read owned disputes and respond to evidence requests | Playwright and integration | `disputes.real-backend.spec.ts` and the disputes scenario |
| GET and POST `/api/operations/disputes/[[...casePath]]` | List disputes, request evidence, and resolve cases for allowlisted operators | Playwright and integration | `disputes.real-backend.spec.ts` and the disputes scenario |
| GET and POST `/api/accounts/[id]/statement-jobs/[[...jobPath]]` | Queue an export, inspect job state, and download it before expiry | Integration | Statement jobs and worker recovery in the [integration guide](../tests/integration/README.md#disputes-and-statement-worker-recovery) |

## Cross-service boundaries

| Boundary | Expected outcome | Test layer | Evidence |
| --- | --- | --- | --- |
| Temporal worker replacement | The replacement worker completes the original run after the signal | Temporal | `TestBankingIntegration` reads the completion event and its worker identity |
| Fraud and compliance | Correct screening calls, and denial or provider failures leave the ledger untouched | Temporal, real Go service | `AssertFraudRejections` checks rule denial and provider failure. Separate read activities verify the three providers, balances and ledger |
| Movement reservations | PostgreSQL serializes competing movements and closure; a durable ledger decision controls settlement or compensation | PostgreSQL service integration | `MovementReservationsPostgresTest` checks row locks, signed account commands and rollback. `TransactionRollbackTest` checks compensation and reconciliation |
| Payments | Local stubs receive the expected payment fan-out | Temporal | `LedgerJourney.VerifyPayments` checks correlated Stripe, PayPal and ComplyAdvantage requests |
| Broker and notification providers | One matching event and one request to each notification provider | Temporal | `Journey.VerifyKafka` reads Kafka independently and `VerifyDeliveries` checks each provider’s request and status |
| MongoDB and consumer recovery | Persist the event and handle restart, duplicates, and provider failures | Temporal recovery | `VerifyMongo` queries the database. `RecoveryJourney` checks offsets, restarts, duplicate delivery and an HTTP 503 |
| Kubernetes, trace propagation, and traffic replay | Smoke and trace checks pass, and replay reports success or an infrastructure skip | Scheduled and manual CI | `kubernetes-smoke.py` checks the deployed frontend and Jaeger trace. The replay job runs `make proxymock-replay` or records why it skipped |
