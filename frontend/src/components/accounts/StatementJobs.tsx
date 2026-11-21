'use client';

import { useEffect, useRef, useState } from 'react';
import Button from '@/components/ui/Button';

type Job = {
  id: string;
  status: 'QUEUED' | 'READY' | 'FAILED' | 'EXPIRED';
  createdAt: string;
  expiresAt?: string;
};

const labels: Record<Job['status'], string> = {
  QUEUED: 'Queued',
  READY: 'Ready',
  FAILED: 'Failed',
  EXPIRED: 'Expired',
};

export default function StatementJobs({ accountId }: { accountId: number }) {
  const path = `/api/accounts/${accountId}/statement-jobs`;
  const [jobs, setJobs] = useState<Job[]>([]);
  const [page, setPage] = useState(1);
  const [hasMore, setHasMore] = useState(false);
  const [refresh, setRefresh] = useState(0);
  const [sending, setSending] = useState(false);
  const [listError, setListError] = useState('');
  const [createError, setCreateError] = useState('');
  const [notice, setNotice] = useState('');
  const key = useRef<string | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    let next: ReturnType<typeof setTimeout>;
    async function load() {
      try {
        const response = await fetch(`${path}?page=${page}`, { cache: 'no-store', signal: controller.signal });
        if (!response.ok) throw new Error('Could not load statement exports.');
        const result: { items: Job[]; hasMore: boolean } = await response.json();
        if (controller.signal.aborted) return;
        setJobs(result.items);
        setHasMore(result.hasMore);
        setListError('');
        next = setTimeout(load, result.items.some(job => job.status === 'QUEUED') ? 2000 : 30000);
      } catch {
        if (controller.signal.aborted) return;
        setListError('Could not load statement exports.');
        next = setTimeout(load, 5000);
      }
    }
    void load();
    return () => {
      controller.abort();
      clearTimeout(next);
    };
  }, [path, page, refresh]);

  async function create() {
    setSending(true);
    setCreateError('');
    key.current ??= crypto.randomUUID();
    try {
      const response = await fetch(path, { method: 'POST', headers: { 'Idempotency-Key': key.current } });
      if (!response.ok) throw new Error('Could not request a statement.');
      const job: Job = await response.json();
      if (page === 1) setJobs(previous => [job, ...previous.filter(item => item.id !== job.id)].slice(0, 20));
      key.current = null;
      setNotice('Statement requested.');
      setPage(1);
      setRefresh(current => current + 1);
    } catch {
      setCreateError('Could not request a statement. Try again.');
    } finally {
      setSending(false);
    }
  }

  return (
    <section className="mb-6 space-y-4 rounded-3xl border border-line bg-surface p-6" aria-labelledby="statement-exports-heading">
      <h2 id="statement-exports-heading" className="text-lg font-medium text-ink">Statement exports</h2>
      <Button onClick={create} disabled={sending}>Request JSON statement</Button>
      {notice && <p role="status">{notice}</p>}
      {createError && <p role="alert" className="text-red-700">{createError}</p>}
      {listError && <p role="alert" className="text-red-700">{listError}</p>}
      {jobs.length > 0 && (
        <ul className="divide-y divide-line">
          {jobs.map(job => (
            <li className="flex flex-wrap items-center gap-x-4 gap-y-1 py-3" key={job.id}>
              <time dateTime={job.createdAt}>{new Date(job.createdAt).toLocaleString()}</time>
              <span>{labels[job.status]}</span>
              {job.status === 'READY' && (
                <a className="font-medium text-brass underline" href={`${path}/${job.id}/download`}>Download JSON</a>
              )}
            </li>
          ))}
        </ul>
      )}
      {(hasMore || page > 1) && (
        <nav aria-label="Statement export pages" className="flex gap-3">
          <Button variant="secondary" disabled={page === 1} onClick={() => setPage(page - 1)}>Previous</Button>
          <Button variant="secondary" disabled={!hasMore} onClick={() => setPage(page + 1)}>Next</Button>
        </nav>
      )}
    </section>
  );
}
