'use client';

import { FormEvent, useState } from 'react';
import AuthenticatedLayout from '@/components/layout/AuthenticatedLayout';
import ProtectedRoute from '@/components/auth/ProtectedRoute';

export default function KycPage() {
  return <ProtectedRoute><AuthenticatedLayout><Form /></AuthenticatedLayout></ProtectedRoute>;
}

function Form() {
  const [status, setStatus] = useState('');
  const [error, setError] = useState('');

  async function refresh() {
    const response = await fetch('/api/kyc');
    const body = await response.json();
    if (!response.ok) setError(body.message || 'Could not read KYC status');
    else setStatus(body.status || 'NONE');
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    setError('');
    const response = await fetch('/api/kyc', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ fullName: data.get('fullName'), documentReference: data.get('documentReference') }),
    });
    const body = await response.json();
    if (!response.ok) setError(body.message || 'Application failed');
    else setStatus(body.status);
  }

  return (
    <form onSubmit={submit} className="mx-auto max-w-lg space-y-4 p-6">
      <h1 className="text-2xl font-semibold">Identity review</h1>
      <p>Status: {status || 'not loaded'}</p>
      {error && <p role="alert">{error}</p>}
      <label className="block">Full name<input name="fullName" className="mt-1 w-full rounded border p-2" required /></label>
      <label className="block">Document reference<input name="documentReference" className="mt-1 w-full rounded border p-2" required /></label>
      <div className="flex gap-3">
        <button className="rounded bg-blue-700 px-4 py-2 text-white" type="submit">Submit</button>
        <button className="rounded border px-4 py-2" type="button" onClick={refresh}>Refresh status</button>
      </div>
    </form>
  );
}
