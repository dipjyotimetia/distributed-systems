'use client';

import { FormEvent, useEffect, useRef, useState } from 'react';
import ProtectedRoute from '@/components/auth/ProtectedRoute';
import AuthenticatedLayout from '@/components/layout/AuthenticatedLayout';
import Button from '@/components/ui/Button';
import { AccountsAPI, type Account } from '@/lib/api/accounts';

type Payment = { id: string; status: string; clearingReference?: string; settlementBusinessDate?: string };
const inputClass = 'mt-1 block w-full rounded-xl border border-line bg-surface p-2.5 text-ink focus:border-brass focus:outline-none focus:ring-2 focus:ring-brass';

function Form() {
  const retry = useRef<{ body: string; key: string } | null>(null);
  const [payment, setPayment] = useState<Payment>();
  const [pending, setPending] = useState(false);
  const [error, setError] = useState('');
  const [accounts, setAccounts] = useState<Account[]>();
  const [accountsError, setAccountsError] = useState(false);

  useEffect(() => {
    let active = true;
    async function load() {
      try {
        const response = await AccountsAPI.getAccounts();
        if (!active) return;
        if (!response.success || !response.data) {
          setAccountsError(true);
          return;
        }
        setAccounts(response.data.filter(account => account.currency === 'AUD' && account.status === 'ACTIVE'));
      } catch {
        if (active) setAccountsError(true);
      }
    }
    void load();
    return () => { active = false; };
  }, []);

  async function request(id?: string, submission?: { body: string; key: string }) {
    setPending(true);
    setError('');
    try {
      const response = await fetch(id ? '/api/payments/' + id : '/api/payments', {
        cache: 'no-store',
        ...(submission && {
          method: 'POST', body: submission.body,
          headers: { 'Content-Type': 'application/json', 'Idempotency-Key': submission.key },
        }),
      });
      if (!response.ok) throw new Error(response.status === 409 ? 'This request conflicts with a previous payment.' : 'Could not access the payment.');
      setPayment(await response.json());
      if (submission) retry.current = null;
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : 'Could not access the payment.');
    } finally {
      setPending(false);
    }
  }

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const fields = new FormData(event.currentTarget);
    const accountId = Number(fields.get('account'));
    if (!Number.isSafeInteger(accountId) || accountId <= 0) {
      setError('Enter a valid account ID.');
      return;
    }
    const body = JSON.stringify({
      accountId, destination: fields.get('destination'), amountMinor: Number(fields.get('amount')),
    });
    if (retry.current?.body !== body) retry.current = { body, key: crypto.randomUUID() };
    void request(undefined, retry.current);
  }

  return (
    <section className="mx-auto max-w-xl space-y-5 rounded-3xl border border-line bg-surface p-5 sm:p-8">
      <p className="text-xs font-medium uppercase tracking-[0.18em] text-brass">Payments</p>
      <h1 className="text-3xl font-semibold tracking-tight text-ink">Outbound payment</h1>
      <form className="space-y-4" onSubmit={submit}>
        <label className="block">Source account
          <select className={inputClass} name="account" required defaultValue="" disabled={!accounts?.length}>
            <option value="" disabled>Select an AUD account</option>
            {accounts?.map(account => (
              <option key={account.id} value={account.id}>
                {account.accountType} · ending {account.accountNumber.slice(-4)}
              </option>
            ))}
          </select>
        </label>
        {accountsError && <p role="alert" className="text-red-700">Accounts could not be loaded.</p>}
        {!accountsError && !accounts && <p role="status">Loading AUD accounts…</p>}
        {accounts?.length === 0 && <p role="status">You need an active AUD account to send an outbound payment.</p>}
        <label className="block">Destination code
          <input className={inputClass} name="destination" pattern="[A-Z0-9]{12}" maxLength={12} required />
        </label>
        <label className="block">Amount in AUD cents
          <input className={inputClass} name="amount" type="number" min="1" max="999999999999" step="1" required />
        </label>
        <Button type="submit" disabled={pending || !accounts?.length}>Submit payment</Button>
      </form>
      {error && <p role="alert" className="text-red-700">{error}</p>}
      {payment && (
        <div className="space-y-2">
          <div role="status">
            <p>Payment <span className="break-all">{payment.id}</span></p>
            <p>{payment.status.replaceAll('_', ' ')}</p>
            {payment.clearingReference && <p>Clearing reference {payment.clearingReference}</p>}
              {payment.settlementBusinessDate && <p>Settlement date {payment.settlementBusinessDate}</p>}
          </div>
          <Button type="button" disabled={pending} onClick={() => void request(payment.id)}>Refresh status</Button>
        </div>
      )}
    </section>
  );
}

export default function PaymentForm() {
  return (
    <ProtectedRoute>
      <AuthenticatedLayout><Form /></AuthenticatedLayout>
    </ProtectedRoute>
  );
}
