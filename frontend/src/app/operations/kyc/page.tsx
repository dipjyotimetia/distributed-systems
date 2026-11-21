'use client';

import { useEffect, useState } from 'react';
import AuthenticatedLayout from '@/components/layout/AuthenticatedLayout';
import ProtectedRoute from '@/components/auth/ProtectedRoute';
import Button from '@/components/ui/Button';
import PageHeader from '@/components/ui/PageHeader';

type Item = { id: string; fullName: string; documentReference: string };

export default function KycQueuePage() {
  return (
    <ProtectedRoute>
      <AuthenticatedLayout>
        <Queue />
      </AuthenticatedLayout>
    </ProtectedRoute>
  );
}

function Queue() {
  const [items, setItems] = useState<Item[]>([]);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);
  const [decidingId, setDecidingId] = useState<string | null>(null);

  async function load() {
    setError('');
    setLoading(true);
    try {
      const response = await fetch('/api/v1/operations/kyc');
      const body = await response.json().catch(() => ({}));
      if (!response.ok) setError(body.message || 'Queue unavailable');
      else setItems(body.cases || []);
    } catch {
      setError('Queue unavailable');
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    let active = true;

    async function loadQueue() {
      try {
        const response = await fetch('/api/v1/operations/kyc');
        const body = await response.json().catch(() => ({}));
        if (!active) return;
        if (!response.ok) setError(body.message || 'Queue unavailable');
        else setItems(body.cases || []);
      } catch {
        if (active) setError('Queue unavailable');
      } finally {
        if (active) setLoading(false);
      }
    }

    void loadQueue();
    return () => {
      active = false;
    };
  }, []);

  async function decide(caseId: string, approve: boolean) {
    setDecidingId(caseId);
    setError('');
    try {
      const response = await fetch('/api/v1/operations/kyc', {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ caseId, approve }),
      });
      if (!response.ok) {
        const body = await response.json().catch(() => ({}));
        setError(body.message || 'Decision failed');
      } else {
        await load();
      }
    } catch {
      setError('Decision failed');
    } finally {
      setDecidingId(null);
    }
  }

  return (
    <section className='mx-auto max-w-3xl space-y-4'>
      <PageHeader
        eyebrow='Operations'
        title='KYC review queue'
        description='Approve or reject identity applications waiting for an operator.'
        actions={
          <Button variant='outline' onClick={load} isLoading={loading}>
            Refresh queue
          </Button>
        }
      />
      {error && (
        <p
          role='alert'
          className='rounded-2xl border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-800'
        >
          {error}
        </p>
      )}
      {loading && items.length === 0 && (
        <p role='status' className='text-sm text-ink/70'>
          Loading queue...
        </p>
      )}
      {!loading && !error && items.length === 0 && (
        <p className='rounded-3xl border border-dashed border-line bg-surface px-6 py-12 text-center text-ink/70'>
          No identity applications are waiting.
        </p>
      )}
      <ul className='space-y-3'>
        {items.map(item => (
          <li
            key={item.id}
            className='rounded-3xl border border-line bg-surface p-5'
          >
            <p className='font-medium text-ink'>{item.fullName}</p>
            <p className='mt-1 text-sm text-ink/70'>{item.documentReference}</p>
            <div className='mt-4 flex gap-2'>
              <Button
                type='button'
                isLoading={decidingId === item.id}
                onClick={() => decide(item.id, true)}
              >
                Approve
              </Button>
              <Button
                type='button'
                variant='outline'
                disabled={decidingId === item.id}
                onClick={() => decide(item.id, false)}
              >
                Reject
              </Button>
            </div>
          </li>
        ))}
      </ul>
    </section>
  );
}
