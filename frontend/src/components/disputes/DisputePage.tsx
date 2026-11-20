'use client';

import { FormEvent, useEffect, useRef, useState } from 'react';
import ProtectedRoute from '@/components/auth/ProtectedRoute';
import AuthenticatedLayout from '@/components/layout/AuthenticatedLayout';
import Button from '@/components/ui/Button';
import { Dispute, DisputeQueue, requestDisputes } from '@/lib/api/disputes';

function DisputeForm({ label, limit, submitLabel, onSubmit }: {
  label: string;
  limit: number;
  submitLabel: string;
  onSubmit: (value: string, key: string) => Promise<void>;
}) {
  const command = useRef<{ text: string; key: string } | null>(null);
  const [value, setValue] = useState('');
  const [sending, setSending] = useState(false);
  const [error, setError] = useState('');

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError('');
    setSending(true);
    try {
      const text = value.trim();
      if (command.current?.text !== text) command.current = { text, key: crypto.randomUUID() };
      await onSubmit(text, command.current.key);
      command.current = null;
      setValue('');
    } catch (error) {
      setError(error instanceof Error ? error.message : 'Could not save dispute');
    } finally {
      setSending(false);
    }
  }

  return (
    <form className="mt-4 space-y-3" onSubmit={submit}>
      <label className="block text-sm font-medium text-gray-800">
        {label}
        <textarea
          className="mt-2 block w-full rounded-md border border-gray-300 p-2 text-gray-900"
          value={value}
          onChange={event => setValue(event.target.value)}
          maxLength={limit}
          required
          rows={3}
        />
      </label>
      {error && <p role="alert" className="text-sm text-red-700">{error}</p>}
      <Button type="submit" disabled={sending || !value.trim()}>{submitLabel}</Button>
    </form>
  );
}

function Cases({ operations, transferId }: { operations: boolean; transferId?: number }) {
  const [items, setItems] = useState<Dispute[]>([]);
  const [page, setPage] = useState(1);
  const [hasMore, setHasMore] = useState(false);
  const [revision, refresh] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [filed, setFiled] = useState(false);
  const prefix = operations ? '/api/operations/disputes' : '/api/disputes';

  useEffect(() => {
    const controller = new AbortController();
    async function load() {
      try {
        if (operations) {
          const result = await requestDisputes<DisputeQueue>(`${prefix}?page=${page}`, undefined, controller.signal);
          if (controller.signal.aborted) return;
          setItems(result.items);
          setHasMore(result.hasMore);
        } else {
          const result = await requestDisputes<Dispute[]>(prefix, undefined, controller.signal);
          if (controller.signal.aborted) return;
          setItems(result);
        }
      } catch (error) {
        if (!controller.signal.aborted) {
          setError(error instanceof Error ? error.message : 'Could not load disputes');
        }
      } finally {
        if (!controller.signal.aborted) setLoading(false);
      }
    }
    void load();
    return () => controller.abort();
  }, [operations, page, prefix, revision]);

  function reload() {
    setLoading(true);
    setError('');
    refresh(current => current + 1);
  }

  return (
    <div className="mx-auto max-w-3xl space-y-5">
      <p className="text-xs font-medium uppercase tracking-[0.18em] text-brass">{operations ? 'Operations' : 'Disputes'}</p>
      <h1 className="text-3xl font-semibold tracking-tight text-ink">{operations ? 'Dispute operations' : 'Transfer disputes'}</h1>
      {notice && <p role="status" className="text-green-800">{notice}</p>}
      {transferId && !filed && (
        <section className="rounded-3xl border border-line bg-surface p-5">
          <h2 className="font-semibold">Dispute transfer #{transferId}</h2>
          <DisputeForm label="Reason" limit={500} submitLabel="File dispute" onSubmit={async reason => {
            const dispute = await requestDisputes<Dispute>(prefix, { transferId, reason });
            setNotice(`Dispute #${dispute.id} is on file.`);
            setFiled(true);
            reload();
          }} />
        </section>
      )}
      {loading && <p role="status">Loading disputes…</p>}
      {error && <p role="alert" className="text-red-700">{error}</p>}
      {!loading && !error && items.length === 0 && <p>No disputes to display.</p>}
      {!loading && !error && items.map(item => (
        <article key={item.id} className="space-y-2 rounded-3xl border border-line bg-surface p-5">
          <h2 className="font-semibold">Dispute #{item.id} · Transfer #{item.transferId}</h2>
          <p>{item.status === 'OPEN' ? 'Open' : item.status === 'ESCALATED' ? 'Escalated' : 'Resolved'}</p>
          <p className="text-sm text-gray-700">Filed {new Date(item.createdAt).toLocaleString()}</p>
          <p className="whitespace-pre-wrap break-words">{item.reason}</p>
          {item.resolution && <p className="whitespace-pre-wrap break-words"><strong>Resolution</strong><br />{item.resolution}</p>}
          {item.evidenceEnabled && <p className="text-sm">{{
            OPERATOR_REVIEW: 'Operator review',
            WAITING_FOR_EVIDENCE: 'Waiting for evidence',
            CLOSED: 'Investigation closed',
          }[item.investigationStage]}</p>}
          {item.evidenceRequest && (
            <section className="space-y-2">
              <h3 className="font-medium">Evidence request</h3>
              <p className="whitespace-pre-wrap break-words">{item.evidenceRequest.prompt}</p>
              <p className="text-sm">Due {new Date(item.evidenceRequest.deadline).toLocaleString()}</p>
              {item.evidenceRequest.response && <p className="whitespace-pre-wrap break-words">
                <strong>Customer response</strong><br />{item.evidenceRequest.response}
              </p>}
              {!operations && item.investigationStage === 'WAITING_FOR_EVIDENCE' && (
                <DisputeForm label="Written response" limit={2000} submitLabel="Send response" onSubmit={async (text, key) => {
                  await requestDisputes(`${prefix}/${item.id}/evidence-requests/${item.evidenceRequest!.id}/response`, { text }, undefined, key);
                  reload();
                }} />
              )}
            </section>
          )}
          {operations && item.evidenceEnabled && !item.evidenceRequest && (
            <DisputeForm label="Evidence request" limit={2000} submitLabel="Request evidence" onSubmit={async (text, key) => {
              await requestDisputes(`${prefix}/${item.id}/evidence-requests`, { text }, undefined, key);
              reload();
            }} />
          )}
          {operations && (
            <DisputeForm label="Resolution summary" limit={2000} submitLabel="Resolve dispute" onSubmit={async resolution => {
              await requestDisputes<Dispute>(`${prefix}/${item.id}/resolve`, { resolution });
              setNotice(`Dispute #${item.id} was resolved.`);
              setPage(1);
              reload();
            }} />
          )}
        </article>
      ))}
      {!loading && !error && operations && (page > 1 || hasMore) && (
        <nav className="flex items-center gap-3" aria-label="Dispute pages">
          <Button variant="secondary" disabled={page === 1} onClick={() => { setLoading(true); setPage(page - 1); }}>Previous</Button>
          <span>Page {page}</span>
          <Button variant="secondary" disabled={!hasMore} onClick={() => { setLoading(true); setPage(page + 1); }}>Next</Button>
        </nav>
      )}
      {!loading && <Button variant="secondary" onClick={reload}>Refresh disputes</Button>}
    </div>
  );
}

export default function DisputePage({ operations = false, transferId }: { operations?: boolean; transferId?: number }) {
  return (
    <ProtectedRoute>
      <AuthenticatedLayout>
        <Cases operations={operations} transferId={transferId} />
      </AuthenticatedLayout>
    </ProtectedRoute>
  );
}
