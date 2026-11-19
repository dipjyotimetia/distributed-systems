export interface Dispute {
  id: number;
  transferId: number;
  reason: string;
  status: 'OPEN' | 'ESCALATED' | 'RESOLVED';
  createdAt: string;
  resolution?: string;
  evidenceEnabled: boolean;
  investigationStage: 'OPERATOR_REVIEW' | 'WAITING_FOR_EVIDENCE' | 'CLOSED';
  evidenceRequest?: {
    id: string;
    prompt: string;
    deadline: string;
    closedAt?: string;
    response?: string;
  };
}

export interface DisputeQueue {
  items: Dispute[];
  hasMore: boolean;
}

export async function requestDisputes<T>(path: string, body?: object, signal?: AbortSignal, key?: string): Promise<T> {
  const response = await fetch(path, {
    method: body ? 'POST' : 'GET',
    ...(body && { headers: { 'Content-Type': 'application/json', ...(key && { 'Idempotency-Key': key }) }, body: JSON.stringify(body) }),
    cache: 'no-store',
    signal,
  });
  const payload = await response.json().catch(() => null);
  if (!response.ok) {
    throw new Error(payload?.message || 'Could not access disputes');
  }
  return payload;
}
