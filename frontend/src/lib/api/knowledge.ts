export type DocType =
  | 'POLICY'
  | 'KYC_EVIDENCE'
  | 'DISPUTE_EVIDENCE'
  | 'STATEMENT'
  | 'OTHER';

export interface KnowledgeDocument {
  id: string;
  title: string;
  docType: DocType;
  visibility: 'OWNER' | 'BANK' | 'PUBLIC_BANK';
  contentType: string;
  sizeBytes: number;
  ownerUserId?: number | null;
  caseId?: string | null;
  accountId?: string | null;
  createdAt: string;
}

export interface SearchHit {
  documentId: string;
  title: string;
  docType: DocType;
  score: number;
  excerpt: string;
  citations?: string[];
}

export async function listKnowledgeDocuments(signal?: AbortSignal): Promise<KnowledgeDocument[]> {
  const response = await fetch('/api/v1/knowledge/documents', { cache: 'no-store', signal });
  const payload = await response.json().catch(() => null);
  if (!response.ok) {
    throw new Error(payload?.detail || payload?.error || 'Could not list documents');
  }
  return payload.items || [];
}

export async function searchKnowledge(
  query: string,
  docType?: DocType,
  signal?: AbortSignal,
): Promise<SearchHit[]> {
  const response = await fetch('/api/v1/knowledge/search', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ query, ...(docType ? { docType } : {}), limit: 5 }),
    cache: 'no-store',
    signal,
  });
  const payload = await response.json().catch(() => null);
  if (!response.ok) {
    throw new Error(payload?.detail || payload?.error || 'Could not search knowledge');
  }
  return payload.hits || [];
}

export async function uploadKnowledgeDocument(form: FormData, signal?: AbortSignal): Promise<KnowledgeDocument> {
  const response = await fetch('/api/v1/knowledge/documents', {
    method: 'POST',
    body: form,
    cache: 'no-store',
    signal,
  });
  const payload = await response.json().catch(() => null);
  if (!response.ok) {
    throw new Error(payload?.detail || payload?.error || 'Could not upload document');
  }
  return payload;
}
