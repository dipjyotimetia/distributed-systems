'use client';

import { FormEvent, useEffect, useRef, useState } from 'react';
import Button from '@/components/ui/Button';
import {
  Configuration, PaymentReviewApi, ResponseError, type PaymentReview, type PaymentResolutionRequest,
} from '@/gen/openapi/payments/src';

const client = new PaymentReviewApi(new Configuration({ basePath: '' }));
const pageSize = 50;
const formatAud = new Intl.NumberFormat('en-AU', { style: 'currency', currency: 'AUD' });

function ReviewCard({ review, refresh }: { review: PaymentReview; refresh: () => void }) {
  const [message, setMessage] = useState('');
  const [busy, setBusy] = useState(false);
  const submission = useRef<{ fingerprint: string; idempotencyKey: string }>(undefined);
  const eligible = review.evidence.filter(item => item.kind !== 'ACCEPTED'
    && item.amountMinor === review.heldAmountMinor
    && (!review.clearingReference || item.clearingReference === review.clearingReference));

  async function resolve(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (busy) return;
    const form = new FormData(event.currentTarget);
    const evidence = eligible.find(item => item.id === form.get('evidence'));
    const reason = String(form.get('reason') ?? '').trim();
    if (!reason || !evidence) return;
    const body: PaymentResolutionRequest = {
      expectedRevision: review.revision, evidenceId: evidence.id,
      action: evidence.kind === 'SETTLED' ? 'CONFIRM_SETTLEMENT' : 'CONFIRM_REJECTION', reason,
    };
    const fingerprint = JSON.stringify(body);
    if (submission.current?.fingerprint !== fingerprint) {
      submission.current = { fingerprint, idempotencyKey: crypto.randomUUID() };
    }
    setBusy(true);
    try {
      await client.resolvePaymentReview({
        id: review.paymentId, idempotencyKey: submission.current.idempotencyKey, paymentResolutionRequest: body,
      });
      setMessage('Resolution recorded. The worker will apply it to the ledger.');
      refresh();
    } catch (error) {
      if (error instanceof ResponseError && error.response.status === 409) {
        setMessage('The review changed. Check the latest evidence and revision.');
        refresh();
      } else {
        setMessage('Could not confirm the resolution. Retry the same decision to recover its status.');
      }
    } finally {
      setBusy(false);
    }
  }

  return (
    <article className="space-y-3 rounded-2xl border border-line bg-surface p-4">
      <p className="font-medium">{review.reason.toLowerCase().replaceAll('_', ' ')}</p>
      <p className="break-all text-sm">Payment {review.paymentId} · revision {review.revision}</p>
      <p className="text-sm">Held {formatAud.format(review.heldAmountMinor / 100)} · age {Math.floor(review.ageSeconds / 60)} min</p>
      {review.resolution && <p className="text-sm">Resolution {review.resolution.status.toLowerCase()}</p>}
      {review.evidence.length > 0 && (
        <ul className="space-y-1 text-sm">
          {review.evidence.map(item => (
            <li key={item.id}>{item.kind} · {formatAud.format(item.amountMinor / 100)} · {item.clearingReference}</li>
          ))}
        </ul>
      )}
      {review.status === 'REVIEW_REQUIRED' && !review.resolution && eligible.length > 0 && (
        <form className="space-y-2" onSubmit={resolve}>
          <label className="block text-sm">Evidence and disposition
            <select className="ml-2 rounded border border-line p-1" name="evidence" required disabled={busy}>
              {eligible.map(item => (
                <option key={item.id} value={item.id}>
                  {item.kind === 'SETTLED' ? 'Confirm settlement' : 'Confirm rejection'} · {item.clearingReference}
                </option>
              ))}
            </select>
          </label>
          <label className="block text-sm">Reason
            <textarea className="block w-full rounded border border-line p-2" name="reason" maxLength={1000} required disabled={busy} />
          </label>
          <Button type="submit" disabled={busy}>Record resolution</Button>
        </form>
      )}
      {message && <p role="status" className="text-sm">{message}</p>}
    </article>
  );
}

export default function ReviewQueue() {
  const [reviews, setReviews] = useState<PaymentReview[]>();
  const [offset, setOffset] = useState(0);
  const [revision, setRevision] = useState(0);
  const [error, setError] = useState('');
  const refresh = () => setRevision(current => current + 1);

  useEffect(() => {
    const controller = new AbortController();
    void client.listPaymentReviews({ offset }, { signal: controller.signal, cache: 'no-store' }).then(result => {
      setReviews(result);
      setError('');
    }).catch(() => {
      if (!controller.signal.aborted) setError('The payment review queue is unavailable to this account.');
    });
    return () => controller.abort();
  }, [offset, revision]);

  return (
    <section className="mx-auto mt-8 max-w-6xl space-y-4">
      <h2 className="text-2xl font-semibold">Payment reviews</h2>
      {error && <p role="alert">{error}</p>}
      {reviews?.length === 0 && <p role="status">No reviews on this page.</p>}
      {reviews?.map(review => <ReviewCard key={review.paymentId} review={review} refresh={refresh} />)}
      <div className="flex gap-2">
        <Button variant="outline" onClick={refresh}>Refresh reviews</Button>
        {offset > 0 && <Button variant="outline" onClick={() => setOffset(offset - pageSize)}>Previous</Button>}
        {reviews?.length === pageSize && <Button variant="outline" onClick={() => setOffset(offset + pageSize)}>Next</Button>}
      </div>
    </section>
  );
}
