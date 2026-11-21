'use client';

import { useEffect, useState } from 'react';
import AuthenticatedLayout from '@/components/layout/AuthenticatedLayout';
import ProtectedRoute from '@/components/auth/ProtectedRoute';
import PageHeader from '@/components/ui/PageHeader';

type Notice = {
  transaction_id?: string;
  payment_id?: string;
  transaction_type?: string;
  status?: string;
  amount?: number;
  timestamp?: string;
};

export default function NotificationsPage() {
  return (
    <ProtectedRoute>
      <AuthenticatedLayout>
        <Inbox />
      </AuthenticatedLayout>
    </ProtectedRoute>
  );
}

function Inbox() {
  const [notices, setNotices] = useState<Notice[]>([]);
  const [state, setState] = useState<'loading' | 'ready' | 'error'>('loading');

  useEffect(() => {
    let active = true;
    async function load() {
      try {
        const response = await fetch('/api/notifications', { cache: 'no-store' });
        const body = await response.json();
        if (!active) return;
        if (!response.ok || !Array.isArray(body)) {
          setState('error');
          return;
        }
        setNotices(body);
        setState('ready');
      } catch {
        if (active) setState('error');
      }
    }
    void load();
    return () => { active = false; };
  }, []);

  return (
    <div className="mx-auto max-w-3xl">
      <PageHeader
        eyebrow="Notifications"
        title="Activity"
        description="Transaction events for the account you are signed in with."
      />
      {state === 'loading' && <p role="status" className="text-sm text-ink/70">Loading notifications…</p>}
      {state === 'error' && (
        <p role="alert" className="rounded-2xl border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-800">
          Notifications could not be loaded.
        </p>
      )}
      {state === 'ready' && notices.length === 0 && (
        <p className="rounded-3xl border border-dashed border-line bg-surface px-6 py-12 text-center text-ink/70">
          No notifications yet.
        </p>
      )}
      {state === 'ready' && notices.length > 0 && (
        <ul className="space-y-3">
          {notices.map((notice, index) => (
            <li key={`${notice.transaction_id || notice.payment_id || 'notice'}-${index}`} className="rounded-3xl border border-line bg-surface p-5">
              <p className="text-sm font-medium text-ink">
                {(notice.transaction_type || 'Activity').replaceAll('_', ' ')} · {notice.status || 'Recorded'}
              </p>
              {notice.amount != null && <p className="mt-1 text-sm text-ink/70">{notice.amount}</p>}
              {notice.timestamp && <p className="mt-1 text-sm text-ink/60">{new Date(notice.timestamp).toLocaleString()}</p>}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
