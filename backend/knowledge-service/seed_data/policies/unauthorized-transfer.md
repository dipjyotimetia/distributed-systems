# Unauthorized Transfer Investigation Policy

When a customer reports an unauthorized transfer:

1. Confirm the ledger movement exists and belongs to the customer.
2. Collect a written statement describing the unrecognized merchant or counterparty.
3. Check KYC status and recent card/device signals when available.
4. Prefer REQUEST_EVIDENCE before REFUND when the customer narrative is incomplete.
5. Prefer REFUND when the bank confirms the transfer was not authorized by the customer
   and no customer negligence is evident.
6. Prefer DENY when the customer previously authenticated the transfer through MFA and
   no fraud signal is present.

Policy notes for AI research drafts must cite this document when recommending REFUND
or DENY for unauthorized claims.
