'use client';

import { FormEvent, useEffect, useState } from 'react';
import AuthenticatedLayout from '@/components/layout/AuthenticatedLayout';
import ProtectedRoute from '@/components/auth/ProtectedRoute';
import Button from '@/components/ui/Button';
import Input from '@/components/ui/Input';
import PageHeader from '@/components/ui/PageHeader';
import StatusPill from '@/components/ui/StatusPill';

type LatestCase = {
  fullName?: string;
  documentReference?: string;
  status?: string;
};

export default function KycPage() {
  return (
    <ProtectedRoute>
      <AuthenticatedLayout>
        <Form />
      </AuthenticatedLayout>
    </ProtectedRoute>
  );
}

function Form() {
  const [status, setStatus] = useState('');
  const [latest, setLatest] = useState<LatestCase | null>(null);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    let active = true;

    async function load() {
      try {
        const response = await fetch('/api/v1/kyc');
        const body = await response.json().catch(() => ({}));
        if (!active) return;
        if (!response.ok) setError(body.message || 'Could not read KYC status');
        else {
          setStatus(body.status || 'NONE');
          setLatest(body.latest ?? null);
        }
      } catch {
        if (active) setError('Could not read KYC status');
      } finally {
        if (active) setLoading(false);
      }
    }

    void load();
    return () => {
      active = false;
    };
  }, []);

  async function refresh() {
    setError('');
    setLoading(true);
    try {
      const response = await fetch('/api/v1/kyc');
      const body = await response.json().catch(() => ({}));
      if (!response.ok) setError(body.message || 'Could not read KYC status');
      else {
        setStatus(body.status || 'NONE');
        setLatest(body.latest ?? null);
      }
    } catch {
      setError('Could not read KYC status');
    } finally {
      setLoading(false);
    }
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    setError('');
    setSubmitting(true);
    try {
      const response = await fetch('/api/v1/kyc', {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({
          fullName: data.get('fullName'),
          documentReference: data.get('documentReference'),
        }),
      });
      const body = await response.json().catch(() => ({}));
      if (!response.ok) setError(body.message || 'Application failed');
      else {
        setStatus(body.status || 'PENDING');
        setLatest(body);
      }
    } catch {
      setError('Application failed');
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <form onSubmit={submit} className='mx-auto max-w-lg space-y-4'>
      <PageHeader
        eyebrow='Identity'
        title='Identity review'
        description='Submit the name and document reference used for review.'
      />
      <div className='rounded-2xl border border-line bg-surface px-4 py-3 text-sm text-ink'>
        {loading ? (
          <p role='status'>Loading identity status...</p>
        ) : (
          <div className='flex flex-wrap items-center gap-3'>
            <span>Status</span>
            <StatusPill status={status || 'NONE'} />
          </div>
        )}
        {latest?.fullName && (
          <p className='mt-2 text-ink/70'>
            {latest.fullName} · {latest.documentReference}
          </p>
        )}
      </div>
      {error && (
        <p role='alert' className='text-sm text-red-700'>
          {error}
        </p>
      )}
      <Input name='fullName' label='Full name' required />
      <Input name='documentReference' label='Document reference' required />
      <div className='flex gap-3'>
        <Button type='submit' isLoading={submitting}>
          Submit
        </Button>
        <Button
          type='button'
          variant='outline'
          onClick={refresh}
          isLoading={loading && !submitting}
        >
          Refresh status
        </Button>
      </div>
    </form>
  );
}
