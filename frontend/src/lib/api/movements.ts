'use client';

import { useEffect, useRef, useState } from 'react';
import { useAuth } from '@/lib/auth/context';
import { Configuration, MovementsApi, ResponseError, type MovementIntent, type MovementOutcome } from '@/gen/openapi/transactions/src';

type Submission = { key: string; intent: MovementIntent; outcome?: MovementOutcome };
const client = new MovementsApi(new Configuration({ basePath: '' }));
const retryDelay = 2500;

export function useMovement(type: MovementIntent['type'], accountId: string) {
  const { user } = useAuth();
  const storageKey = user ? `banking.movement.${user.id}.${type}.${accountId}` : undefined;
  const [submission, setSubmission] = useState<Submission>();
  const [failure, setFailure] = useState('');
  const [loadedKey, setLoadedKey] = useState<string>();
  const draft = useRef<Submission | undefined>(undefined);
  const ready = Boolean(storageKey && storageKey === loadedKey);

  useEffect(() => {
    let cancelled = false;
    queueMicrotask(() => {
      if (cancelled) return;
      draft.current = undefined;
      setSubmission(undefined);
      setFailure('');
      if (!storageKey) return;
      try {
        const saved = sessionStorage.getItem(storageKey);
        if (saved) {
          const next = JSON.parse(saved) as Submission;
          draft.current = next;
          setSubmission(next);
        }
        setLoadedKey(storageKey);
      } catch {
        setFailure('The browser could not recover the saved movement.');
      }
    });
    return () => { cancelled = true; };
  }, [storageKey]);

  useEffect(() => {
    if (!ready || !storageKey || !submission || (submission.outcome && submission.outcome.status !== 'PENDING')) return;
    const controller = new AbortController();
    let timeout: ReturnType<typeof setTimeout>;
    async function check(current: Submission): Promise<void> {
      try {
        const options = { signal: controller.signal, cache: 'no-store' as const };
        const outcome = current.outcome
          ? await client.getMovement({ id: current.outcome.id }, options)
          : await client.submitMovement({ idempotencyKey: current.key, movementIntent: current.intent }, options);
        if (controller.signal.aborted) return;
        const next = { ...current, outcome };
        draft.current = next;
        sessionStorage.setItem(storageKey!, JSON.stringify(next));
        setFailure('');
        setSubmission(next);
      } catch (error) {
        if (controller.signal.aborted) return;
        if (!current.outcome && error instanceof ResponseError && [400, 404, 422].includes(error.response.status)) {
          sessionStorage.removeItem(storageKey!);
          draft.current = undefined;
          setSubmission(undefined);
          setFailure('The movement could not be accepted. Check the amount, currency, and accounts.');
          return;
        }
        if (error instanceof ResponseError && error.response.status === 409) {
          setFailure('The saved key conflicts with a previous movement. Contact support before submitting again.');
          return;
        }
        setFailure('The movement status is temporarily unavailable. Retrying the saved request.');
        timeout = setTimeout(() => void check(current), retryDelay);
      }
    }
    timeout = setTimeout(() => void check(submission), submission.outcome ? retryDelay : 0);
    return () => {
      clearTimeout(timeout);
      controller.abort();
    };
  }, [ready, storageKey, submission]);

  function submit(intent: MovementIntent) {
    if (!ready || !storageKey || draft.current) return;
    const next = { intent, key: crypto.randomUUID() };
    try {
      sessionStorage.setItem(storageKey, JSON.stringify(next));
      draft.current = next;
      setSubmission(next);
      setFailure('');
    } catch {
      setFailure('This browser could not save the request. The movement was not submitted.');
    }
  }

  function startNew() {
    if (!ready || !storageKey || !draft.current?.outcome || draft.current.outcome.status === 'PENDING') return;
    sessionStorage.removeItem(storageKey);
    draft.current = undefined;
    setSubmission(undefined);
    setFailure('');
  }

  return { submit, startNew, submission: ready ? submission : undefined, failure, ready };
}
