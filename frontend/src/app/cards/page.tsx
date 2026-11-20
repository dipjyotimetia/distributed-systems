'use client';

import { FormEvent, useState } from 'react';
import AuthenticatedLayout from '@/components/layout/AuthenticatedLayout';
import ProtectedRoute from '@/components/auth/ProtectedRoute';

export default function CardsPage() {
  return <ProtectedRoute><AuthenticatedLayout><Form /></AuthenticatedLayout></ProtectedRoute>;
}

function Form() {
  const [token, setToken] = useState('');
  const [lastFour, setLastFour] = useState('');
  const [result, setResult] = useState('');
  const [error, setError] = useState('');

  async function issue(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const accountId = Number(new FormData(event.currentTarget).get('accountId'));
    setError('');
    const response = await fetch('/api/cards', {
      method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ accountId }),
    });
    const body = await response.json();
    if (!response.ok) setError(body.message || 'Card was not issued');
    else { setToken(body.token); setLastFour(body.lastFour); }
  }

  async function authorize(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    setError('');
    const response = await fetch('/api/cards/authorize', {
      method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({
        cardToken: token, idempotencyKey: data.get('idempotencyKey'), amount: data.get('amount'), merchant: data.get('merchant'),
      }),
    });
    const body = await response.json();
    if (!response.ok) setError(body.message || 'Authorization failed');
    else setResult(`${body.approved ? 'Approved' : 'Declined'}: ${body.reason}`);
  }

  return (
    <section className="mx-auto max-w-lg space-y-6 p-6">
      <h1 className="text-2xl font-semibold">Virtual card</h1>
      {error && <p role="alert">{error}</p>}
      <form onSubmit={issue} className="space-y-3">
        <label className="block">Account id<input name="accountId" className="mt-1 w-full rounded border p-2" required /></label>
        <button className="rounded bg-blue-700 px-4 py-2 text-white" type="submit">Issue card</button>
        {token && <p>Token {token} · last four {lastFour}</p>}
      </form>
      <form onSubmit={authorize} className="space-y-3">
        <label className="block">Amount<input name="amount" defaultValue="10.00" className="mt-1 w-full rounded border p-2" required /></label>
        <label className="block">Merchant<input name="merchant" defaultValue="Cafe" className="mt-1 w-full rounded border p-2" required /></label>
        <label className="block">Idempotency key<input name="idempotencyKey" defaultValue="card-demo" className="mt-1 w-full rounded border p-2" required /></label>
        <button className="rounded bg-blue-700 px-4 py-2 text-white" type="submit" disabled={!token}>Authorize</button>
        {result && <p>{result}</p>}
      </form>
    </section>
  );
}
