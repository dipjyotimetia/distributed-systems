'use client';

import { useEffect, useState } from 'react';
import ProtectedRoute from '@/components/auth/ProtectedRoute';
import AuthenticatedLayout from '@/components/layout/AuthenticatedLayout';
import Button from '@/components/ui/Button';

type Discrepancy = {
  businessDate: string;
  recordNumber: number;
  paymentId: string;
  outcome: string;
  expectedAmountMinor: number | null;
  observedAmountMinor: number | null;
  observedReference: string | null;
};

const pageSize = 100;
const formatAmount = new Intl.NumberFormat('en-AU', { style: 'currency', currency: 'AUD' });

function amount(value: number | null) {
  return value === null ? 'Unavailable' : formatAmount.format(value / 100);
}

function Queue() {
  const [offset, setOffset] = useState(0);
  const [rows, setRows] = useState<Discrepancy[]>();
  const [error, setError] = useState('');

  useEffect(() => {
    const controller = new AbortController();
    async function load() {
      try {
        const response = await fetch('/api/operations/payments/reconciliation?offset=' + offset, {
          signal: controller.signal, cache: 'no-store',
        });
        if (response.status === 403) {
          setError('Your account does not have access to the reconciliation queue.');
        } else if (!response.ok) {
          setError('The reconciliation queue could not be loaded.');
        } else {
          setRows(await response.json());
        }
      } catch {
        if (!controller.signal.aborted) setError('The reconciliation queue could not be loaded.');
      }
    }
    void load();
    return () => controller.abort();
  }, [offset]);

  function navigate(next: number) {
    setRows(undefined);
    setError('');
    setOffset(next);
  }

  return (
    <section className="mx-auto max-w-6xl space-y-4">
      <h1 className="text-3xl font-semibold tracking-tight text-ink">Payment reconciliation</h1>
      {error && <p role="alert" className="text-red-700">{error}</p>}
      {!error && !rows && <p role="status">Loading discrepancies…</p>}
      {rows?.length === 0 && <p role="status">No discrepancies on this page.</p>}
      {rows && rows.length > 0 && (
        <ul className="divide-y divide-line rounded-3xl border border-line bg-surface">
          {rows.map(row => (
            <li className="grid gap-3 p-4 sm:grid-cols-2 lg:grid-cols-4" key={row.businessDate + '/' + row.recordNumber}>
              <div>
                <p className="font-medium">{row.outcome.replaceAll('_', ' ')}</p>
                <p className="text-sm text-gray-600">{row.businessDate}</p>
              </div>
              <div>
                <p className="text-sm text-gray-600">Payment ID</p>
                <p className="break-all">{row.paymentId}</p>
              </div>
              <div>
                <p className="text-sm text-gray-600">Expected and observed amount</p>
                <p>{amount(row.expectedAmountMinor)} / {amount(row.observedAmountMinor)}</p>
              </div>
              <div>
                <p className="text-sm text-gray-600">Clearing reference</p>
                <p>{row.observedReference ?? 'Unavailable'}</p>
              </div>
            </li>
          ))}
        </ul>
      )}
      {rows && (offset > 0 || rows.length === pageSize) && (
        <nav className="flex gap-3" aria-label="Reconciliation pages">
          <Button disabled={offset === 0} onClick={() => navigate(offset - pageSize)}>Previous</Button>
          <Button disabled={rows.length !== pageSize} onClick={() => navigate(offset + pageSize)}>Next</Button>
        </nav>
      )}
    </section>
  );
}

export default function ReconciliationQueue() {
  return (
    <ProtectedRoute>
      <AuthenticatedLayout><Queue /></AuthenticatedLayout>
    </ProtectedRoute>
  );
}
