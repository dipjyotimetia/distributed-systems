'use client';

import { FormEvent, useState } from 'react';
import AuthenticatedLayout from '@/components/layout/AuthenticatedLayout';
import ProtectedRoute from '@/components/auth/ProtectedRoute';
import PageHeader from '@/components/ui/PageHeader';

export default function CardsPage() {
  return <ProtectedRoute><AuthenticatedLayout><Form /></AuthenticatedLayout></ProtectedRoute>;
}

function Form() {
  const [token, setToken] = useState('');
  const [lastFour, setLastFour] = useState('');
  const [revealed, setRevealed] = useState(false);
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
    else { setToken(body.token); setLastFour(body.lastFour); setRevealed(false); }
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

  const fieldClass = 'mt-1 w-full rounded-xl border border-line bg-surface p-2.5 text-ink focus:border-brass focus:outline-none focus:ring-2 focus:ring-brass';

  return (
    <div className="mx-auto grid max-w-5xl gap-8 lg:grid-cols-[0.9fr_1.1fr]">
      <div>
        <PageHeader eyebrow="Cards" title="Virtual card" description="Issue a card against an account, then authorize a purchase." />
        <div className="relative overflow-hidden rounded-3xl bg-ink p-6 text-canvas shadow-sm sm:p-8">
          <div className="absolute -right-8 -top-10 h-32 w-32 rounded-full bg-brass/30" />
          <p className="text-xs font-medium uppercase tracking-[0.2em] text-[#d7b56a]">Apex</p>
          <p className="mt-10 font-mono text-2xl tracking-[0.18em]">
            {lastFour ? `•••• •••• •••• ${lastFour}` : '•••• •••• •••• ••••'}
          </p>
          <div className="mt-8 flex items-end justify-between text-sm">
            <div>
              <p className="text-canvas/50">Cardholder</p>
              <p className="mt-1">Virtual</p>
            </div>
            <p className="text-canvas/70">{lastFour ? `last four ${lastFour}` : 'Not issued'}</p>
          </div>
        </div>
        {token && (
          <div className="mt-4 rounded-2xl border border-line bg-surface p-4 text-sm">
            <p>Token {revealed ? token : '••••••••'} · last four {lastFour}</p>
            <button
              type="button"
              className="mt-2 text-sm font-medium text-brass hover:text-ink"
              onClick={() => setRevealed((value) => !value)}
            >
              {revealed ? 'Hide token' : 'Reveal token'}
            </button>
          </div>
        )}
      </div>

      <div className="space-y-6">
        {error && <p role="alert" className="rounded-2xl border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-800">{error}</p>}
        <form onSubmit={issue} className="space-y-3 rounded-3xl border border-line bg-surface p-5">
          <h2 className="text-lg font-semibold text-ink">Issue</h2>
          <label className="block text-sm font-medium text-ink">Account id<input name="accountId" className={fieldClass} required /></label>
          <button className="rounded-full bg-ink px-4 py-2 text-sm font-medium text-canvas" type="submit">Issue card</button>
        </form>
        <form onSubmit={authorize} className="space-y-3 rounded-3xl border border-line bg-surface p-5">
          <h2 className="text-lg font-semibold text-ink">Authorize</h2>
          <label className="block text-sm font-medium text-ink">Amount<input name="amount" defaultValue="10.00" className={fieldClass} required /></label>
          <label className="block text-sm font-medium text-ink">Merchant<input name="merchant" defaultValue="Cafe" className={fieldClass} required /></label>
          <label className="block text-sm font-medium text-ink">Idempotency key<input name="idempotencyKey" defaultValue="card-demo" className={fieldClass} required /></label>
          <button className="rounded-full bg-ink px-4 py-2 text-sm font-medium text-canvas disabled:opacity-50" type="submit" disabled={!token}>Authorize</button>
          {result && <p className="text-sm text-ink">{result}</p>}
        </form>
      </div>
    </div>
  );
}
