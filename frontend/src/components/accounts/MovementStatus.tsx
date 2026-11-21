'use client';

import Button from '@/components/ui/Button';
import type { useMovement } from '@/lib/api/movements';

export default function MovementStatus({ movement }: { movement: ReturnType<typeof useMovement> }) {
  const outcome = movement.submission?.outcome;
  const status = outcome?.status;
  return (
    <div className="mb-6 space-y-2" role="status" aria-live="polite">
      {movement.failure && <p className="text-sm text-red-800">{movement.failure}</p>}
      {movement.submission && (
        <div className="space-y-2 rounded-2xl border border-line bg-surface p-4 text-sm text-ink">
          <p>{status === 'POSTED' ? 'Movement posted.' : status === 'REJECTED' ? 'Movement rejected.' :
            status === 'PENDING' ? 'Movement accepted and awaiting posting.' : 'Confirming your movement request.'}</p>
          {outcome && <p className="break-all">Reference {outcome.id}</p>}
          {outcome?.reasonCode && <p>{outcome.reasonCode.toLowerCase().replaceAll('_', ' ')}</p>}
          {status && status !== 'PENDING' && (
            <Button type="button" variant="outline" onClick={movement.startNew}>Start a new movement</Button>
          )}
        </div>
      )}
    </div>
  );
}
