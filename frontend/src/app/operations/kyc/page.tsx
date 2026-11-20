'use client';

import { useState } from 'react';
import AuthenticatedLayout from '@/components/layout/AuthenticatedLayout';
import ProtectedRoute from '@/components/auth/ProtectedRoute';

type Item = { id: string; fullName: string; documentReference: string };

export default function KycQueuePage() {
  return <ProtectedRoute><AuthenticatedLayout><Queue /></AuthenticatedLayout></ProtectedRoute>;
}

function Queue() {
  const [items, setItems] = useState<Item[]>([]);
  const [error, setError] = useState('');

  async function load() {
    const response = await fetch('/api/operations/kyc');
    const body = await response.json();
    if (!response.ok) setError(body.message || 'Queue unavailable');
    else setItems(body.cases || []);
  }

  async function decide(caseId: string, approve: boolean) {
    const response = await fetch('/api/operations/kyc', {
      method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ caseId, approve }),
    });
    if (!response.ok) setError((await response.json()).message || 'Decision failed');
    else load();
  }

  return (
    <section className="mx-auto max-w-lg space-y-4 p-6">
      <h1 className="text-2xl font-semibold">KYC review queue</h1>
      {error && <p role="alert">{error}</p>}
      <button className="rounded border px-4 py-2" type="button" onClick={load}>Load queue</button>
      <ul className="space-y-3">
        {items.map((item) => (
          <li key={item.id} className="rounded border p-3">
            <p>{item.fullName} · {item.documentReference}</p>
            <div className="mt-2 flex gap-2">
              <button className="rounded bg-blue-700 px-3 py-1 text-white" type="button" onClick={() => decide(item.id, true)}>Approve</button>
              <button className="rounded border px-3 py-1" type="button" onClick={() => decide(item.id, false)}>Reject</button>
            </div>
          </li>
        ))}
      </ul>
    </section>
  );
}
