'use client';

import { FormEvent, useEffect, useState } from 'react';
import Button from '@/components/ui/Button';
import { Configuration, ExactAccountsApi, type ExactBalances, type StatementJob } from '@/gen/openapi/accounts/src';

const client = new ExactAccountsApi(new Configuration({ basePath: '' }));

export default function PeriodStatements({ accountId }: { accountId: number }) {
  const [balances, setBalances] = useState<ExactBalances>();
  const [job, setJob] = useState<StatementJob>();
  const [failure, setFailure] = useState('');
  const [busy, setBusy] = useState(false);
  const jobId = job?.id;
  const status = job?.status;

  useEffect(() => {
    const controller = new AbortController();
    void client.getExactBalances({ accountId }, { signal: controller.signal, cache: 'no-store' })
      .then(setBalances).catch(() => {
        if (!controller.signal.aborted) setFailure('Could not load the account posting snapshot.');
      });
    return () => controller.abort();
  }, [accountId]);

  useEffect(() => {
    if (!jobId || status !== 'QUEUED') return;
    const controller = new AbortController();
    const timer = setInterval(() => {
      void client.getPeriodStatement({ accountId, jobId }, { signal: controller.signal, cache: 'no-store' })
        .then(setJob).catch(() => {
          if (!controller.signal.aborted) setFailure('Statement status is temporarily unavailable.');
        });
    }, 2500);
    return () => {
      clearInterval(timer);
      controller.abort();
    };
  }, [accountId, jobId, status]);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    const start = new Date(String(data.get('start')));
    const end = new Date(String(data.get('end')));
    if (!Number.isFinite(start.getTime()) || !Number.isFinite(end.getTime()) || start >= end || end > new Date()
      || (balances?.postingCoverageStart && start < new Date(balances.postingCoverageStart))) {
      setFailure('Choose a completed period inside the recorded posting coverage.');
      return;
    }
    setBusy(true);
    setFailure('');
    try {
      setJob(await client.createPeriodStatement({ accountId,
        statementPeriod: { start: start.toISOString(), end: end.toISOString() },
      }));
    } catch {
      setFailure('Could not request that period statement.');
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="space-y-3 rounded-3xl border border-line bg-surface p-5">
      <h2 className="text-lg font-semibold">Posted activity</h2>
      {balances && (
        <dl className="grid grid-cols-1 gap-2 text-sm sm:grid-cols-3">
          <div><dt>Posted</dt><dd>{balances.currency} {balances.postedBalance}</dd></div>
          <div><dt>Held</dt><dd>{balances.currency} {balances.heldAmount}</dd></div>
          <div><dt>Available</dt><dd>{balances.currency} {balances.availableBalance}</dd></div>
        </dl>
      )}
      {balances?.postingCoverageStart && <p className="text-sm">
        Posting coverage begins {new Date(balances.postingCoverageStart).toLocaleString()}.
      </p>}
      <form onSubmit={submit} className="flex flex-wrap items-end gap-3">
        <label className="block text-sm">Start (local time)
          <input className="block rounded border border-line p-2" type="datetime-local" name="start" required />
        </label>
        <label className="block text-sm">End (local time)
          <input className="block rounded border border-line p-2" type="datetime-local" name="end" required />
        </label>
        <Button type="submit" disabled={busy || status === 'QUEUED'}>Request period statement</Button>
      </form>
      {failure && <p role="alert" className="text-sm">{failure}</p>}
      {job && <p role="status" className="text-sm">Statement {job.status.toLowerCase()}</p>}
      {status === 'READY' && <a className="underline" href={`/api/v2/accounts/${accountId}/statement-jobs/${jobId}/download`}>
        Download period statement
      </a>}
    </section>
  );
}
