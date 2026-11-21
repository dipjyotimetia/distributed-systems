'use client';

import { FormEvent, useState } from 'react';
import AuthenticatedLayout from '@/components/layout/AuthenticatedLayout';
import ProtectedRoute from '@/components/auth/ProtectedRoute';
import PageHeader from '@/components/ui/PageHeader';

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

  const fieldClass = 'mt-1 w-full rounded-xl border border-line bg-canvas p-2.5 text-ink focus:border-brass focus:outline-none focus:ring-2 focus:ring-brass';

  return (
    <form onSubmit={submit} className="mx-auto max-w-lg space-y-4">
      <PageHeader eyebrow="Identity" title="Identity review" description="Submit the name and document reference used for review." />
      <p className="rounded-2xl border border-line bg-surface px-4 py-3 text-sm text-ink">
        Status: {status || 'not loaded'}
      </p>
      {error && <p role="alert" className="text-sm text-red-700">{error}</p>}
      <label className="block text-sm font-medium text-ink">Full name<input name="fullName" className={fieldClass} required /></label>
      <label className="block text-sm font-medium text-ink">Document reference<input name="documentReference" className={fieldClass} required /></label>
      <div className="flex gap-3">
        <button className="rounded-full bg-ink px-4 py-2 text-sm font-medium text-canvas" type="submit">Submit</button>
        <button className="rounded-full border border-line bg-surface px-4 py-2 text-sm font-medium text-ink" type="button" onClick={refresh}>Refresh status</button>
      </div>
    </form>
  );
}
