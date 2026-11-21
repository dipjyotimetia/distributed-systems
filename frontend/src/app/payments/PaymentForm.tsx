'use client';

import { FormEvent, useEffect, useRef, useState } from 'react';
import ProtectedRoute from '@/components/auth/ProtectedRoute';
import AuthenticatedLayout from '@/components/layout/AuthenticatedLayout';
import Button from '@/components/ui/Button';
import { useAuth } from '@/lib/auth/context';
import { AccountsAPI, type Account } from '@/lib/api/accounts';

type Payment = {
  id: string;
  status: string;
  instruction?: { destination?: string; amountMinor?: number };
  clearingReference?: string;
  settlementBusinessDate?: string;
};
const inputClass = 'mt-1 block w-full rounded-xl border border-line bg-surface p-2.5 text-ink focus:border-brass focus:outline-none focus:ring-2 focus:ring-brass';

function Form() {
  const { user } = useAuth();
  const storageKey = user ? `banking.payment.${user.id}` : undefined;
  const [loadedKey, setLoadedKey] = useState<string>();
  const [hasSavedRequest, setHasSavedRequest] = useState(false);
  const retry = useRef<{ body: string; key: string } | null>(null);
  const recordedPayment = useRef(false);
  const [payment, setPayment] = useState<Payment>();
  const [payments, setPayments] = useState<Payment[]>();
  const [paymentsError, setPaymentsError] = useState(false);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState('');
  const [accounts, setAccounts] = useState<Account[]>();
  const [accountsError, setAccountsError] = useState(false);

  useEffect(() => {
    let cancelled = false;
    queueMicrotask(() => {
      if (cancelled) return;
      retry.current = null;
      setHasSavedRequest(false);
      if (!storageKey) return;
      try {
        const saved = sessionStorage.getItem(storageKey);
        if (saved) {
          retry.current = JSON.parse(saved) as { body: string; key: string };
          setHasSavedRequest(true);
        }
        setLoadedKey(storageKey);
      } catch {
        setError('The browser could not recover the saved payment.');
      }
    });
    return () => { cancelled = true; };
  }, [storageKey]);

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

  useEffect(() => {
    let active = true;
    async function loadPayments() {
      try {
        const response = await fetch('/api/v1/payments', { cache: 'no-store' });
        if (!active) return;
        if (!response.ok) {
          setPaymentsError(true);
          return;
        }
        const body = await response.json();
        if (!recordedPayment.current) setPayments(Array.isArray(body) ? body : []);
      } catch {
        if (active) setPaymentsError(true);
      }
    }
    void loadPayments();
    return () => { active = false; };
  }, []);

  async function request(id?: string, submission?: { body: string; key: string }) {
    setPending(true);
    setError('');
    try {
      const response = await fetch(id ? '/api/v1/payments/' + id : '/api/v1/payments', {
        cache: 'no-store',
        ...(submission && {
          method: 'POST', body: submission.body,
          headers: { 'Content-Type': 'application/json', 'Idempotency-Key': submission.key },
        }),
      });
      if (submission && storageKey && (response.status === 400 || response.status === 422)) {
        sessionStorage.removeItem(storageKey);
        retry.current = null;
        setHasSavedRequest(false);
      }
      if (!response.ok) throw new Error(response.status === 409 ? 'This request conflicts with a previous payment.' : 'Could not access the payment.');
      const saved = await response.json() as Payment;
      recordedPayment.current = true;
      setPayment(saved);
      setPayments((current) => [saved, ...(current ?? []).filter((item) => item.id !== saved.id)]);
      if (submission && storageKey) {
        sessionStorage.removeItem(storageKey);
        retry.current = null;
        setHasSavedRequest(false);
      }
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : 'Could not access the payment.');
    } finally {
      setPending(false);
    }
  }

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!storageKey || storageKey !== loadedKey || retry.current || pending) return;
    const fields = new FormData(event.currentTarget);
    const accountId = Number(fields.get('account'));
    if (!Number.isSafeInteger(accountId) || accountId <= 0) {
      setError('Enter a valid account ID.');
      return;
    }
    const body = JSON.stringify({
      accountId, destination: fields.get('destination'), amountMinor: Number(fields.get('amount')),
    });
    const submission = { body, key: crypto.randomUUID() };
    try {
      sessionStorage.setItem(storageKey, JSON.stringify(submission));
      retry.current = submission;
      setHasSavedRequest(true);
      void request(undefined, submission);
    } catch {
      setError('The browser could not save this payment. The request was not submitted.');
    }
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
        <Button type="submit" disabled={pending || hasSavedRequest || !storageKey || storageKey !== loadedKey || !accounts?.length}>Submit payment</Button>
      </form>
      {hasSavedRequest && (
        <Button variant="outline" disabled={pending} onClick={() => {
          if (retry.current) void request(undefined, retry.current);
        }}>Retry saved payment</Button>
      )}
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
      <section aria-labelledby="payment-history">
        <h2 id="payment-history" className="text-lg font-semibold text-ink">Your payments</h2>
        {paymentsError && <p role="alert" className="mt-2 text-red-700">Payments could not be loaded.</p>}
        {!paymentsError && !payments && <p role="status" className="mt-2 text-sm text-ink/70">Loading payments…</p>}
        {payments?.length === 0 && <p className="mt-2 text-sm text-ink/70">No outbound payments yet.</p>}
        {payments && payments.length > 0 && (
          <ul className="mt-3 space-y-2">
            {payments.map((item) => (
              <li key={item.id} className="rounded-2xl border border-line px-4 py-3 text-sm">
                <p className="break-all font-medium">{item.id}</p>
                <p>{item.status.replaceAll('_', ' ')}</p>
                {item.instruction?.destination && <p>To {item.instruction.destination}</p>}
                {item.instruction?.amountMinor != null && <p>{item.instruction.amountMinor} cents</p>}
              </li>
            ))}
          </ul>
        )}
      </section>
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
