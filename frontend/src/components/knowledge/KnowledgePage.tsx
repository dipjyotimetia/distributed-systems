'use client';

import { FormEvent, useEffect, useState } from 'react';
import {
  DocType,
  KnowledgeDocument,
  SearchHit,
  listKnowledgeDocuments,
  searchKnowledge,
  uploadKnowledgeDocument,
} from '@/lib/api/knowledge';

const uploadTypes: DocType[] = ['KYC_EVIDENCE', 'DISPUTE_EVIDENCE', 'STATEMENT', 'OTHER'];

export default function KnowledgePage() {
  const [documents, setDocuments] = useState<KnowledgeDocument[]>([]);
  const [hits, setHits] = useState<SearchHit[]>([]);
  const [query, setQuery] = useState('dispute timeframe');
  const [title, setTitle] = useState('');
  const [docType, setDocType] = useState<DocType>('DISPUTE_EVIDENCE');
  const [caseId, setCaseId] = useState('');
  const [file, setFile] = useState<File | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [status, setStatus] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  const reload = async (signal?: AbortSignal) => {
    setLoading(true);
    try {
      setDocuments(await listKnowledgeDocuments(signal));
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not load documents');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    const controller = new AbortController();
    void reload(controller.signal);
    return () => controller.abort();
  }, []);

  const onSearch = async (event: FormEvent) => {
    event.preventDefault();
    setStatus(null);
    try {
      setHits(await searchKnowledge(query));
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Search failed');
    }
  };

  const onUpload = async (event: FormEvent) => {
    event.preventDefault();
    if (!file || !title.trim()) {
      setError('Title and file are required');
      return;
    }
    const form = new FormData();
    form.set('file', file);
    form.set('title', title.trim());
    form.set('docType', docType);
    form.set('visibility', 'OWNER');
    if (caseId.trim()) form.set('caseId', caseId.trim());
    try {
      const created = await uploadKnowledgeDocument(form);
      setStatus(`Uploaded ${created.title}`);
      setTitle('');
      setCaseId('');
      setFile(null);
      setError(null);
      await reload();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Upload failed');
    }
  };

  return (
    <div className="mx-auto max-w-5xl space-y-10 px-4 py-10">
      <header className="space-y-2">
        <h1 className="text-3xl font-semibold tracking-tight text-ink">Knowledge vault</h1>
        <p className="max-w-2xl text-ink/70">
          Upload KYC or dispute evidence and search seeded bank policies. Retrieval powers the AI
          assistant and MCP tools.
        </p>
      </header>

      {error && (
        <p role="alert" className="rounded-2xl border border-rose-200 bg-rose-50 px-4 py-3 text-rose-800">
          {error}
        </p>
      )}
      {status && (
        <p role="status" className="rounded-2xl border border-emerald-200 bg-emerald-50 px-4 py-3 text-emerald-900">
          {status}
        </p>
      )}

      <section className="space-y-4">
        <h2 className="text-xl font-medium text-ink">Search policies and evidence</h2>
        <form onSubmit={onSearch} className="flex flex-col gap-3 sm:flex-row">
          <input
            className="flex-1 rounded-2xl border border-line bg-surface px-4 py-3"
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            placeholder="e.g. dispute timeframe 60 days"
            aria-label="Knowledge search query"
          />
          <button type="submit" className="rounded-2xl bg-ink px-5 py-3 text-white">
            Search
          </button>
        </form>
        <ul className="space-y-3">
          {hits.map((hit) => (
            <li key={`${hit.documentId}-${hit.score}`} className="rounded-3xl border border-line bg-surface px-5 py-4">
              <div className="flex items-baseline justify-between gap-3">
                <h3 className="font-medium text-ink">{hit.title}</h3>
                <span className="text-sm text-ink/50">{hit.docType} · {hit.score.toFixed(2)}</span>
              </div>
              <p className="mt-2 whitespace-pre-wrap text-sm text-ink/80">{hit.excerpt}</p>
              {hit.citations?.length ? (
                <p className="mt-2 text-xs text-ink/50">Cite: {hit.citations.join(', ')}</p>
              ) : null}
            </li>
          ))}
        </ul>
      </section>

      <section className="space-y-4">
        <h2 className="text-xl font-medium text-ink">Upload evidence</h2>
        <form onSubmit={onUpload} className="grid gap-3 sm:grid-cols-2">
          <input
            className="rounded-2xl border border-line bg-surface px-4 py-3 sm:col-span-2"
            value={title}
            onChange={(event) => setTitle(event.target.value)}
            placeholder="Document title"
            aria-label="Document title"
          />
          <select
            className="rounded-2xl border border-line bg-surface px-4 py-3"
            value={docType}
            onChange={(event) => setDocType(event.target.value as DocType)}
            aria-label="Document type"
          >
            {uploadTypes.map((type) => (
              <option key={type} value={type}>
                {type}
              </option>
            ))}
          </select>
          <input
            className="rounded-2xl border border-line bg-surface px-4 py-3"
            value={caseId}
            onChange={(event) => setCaseId(event.target.value)}
            placeholder="Case id (optional)"
            aria-label="Case id"
          />
          <input
            className="rounded-2xl border border-line bg-surface px-4 py-3 sm:col-span-2"
            type="file"
            onChange={(event) => setFile(event.target.files?.[0] || null)}
            aria-label="Document file"
          />
          <button type="submit" className="rounded-2xl bg-ink px-5 py-3 text-white sm:col-span-2">
            Upload
          </button>
        </form>
      </section>

      <section className="space-y-4">
        <div className="flex items-center justify-between gap-3">
          <h2 className="text-xl font-medium text-ink">Visible documents</h2>
          <button type="button" className="text-sm text-ink/70 underline" onClick={() => void reload()}>
            Refresh
          </button>
        </div>
        {loading && <p role="status">Loading documents…</p>}
        {!loading && documents.length === 0 && (
          <p className="rounded-3xl border border-dashed border-line px-6 py-10 text-center text-ink/60">
            No documents yet.
          </p>
        )}
        <ul className="space-y-3">
          {documents.map((doc) => (
            <li key={doc.id} className="rounded-3xl border border-line bg-surface px-5 py-4">
              <div className="flex flex-wrap items-baseline justify-between gap-2">
                <h3 className="font-medium text-ink">{doc.title}</h3>
                <span className="text-sm text-ink/50">
                  {doc.docType} · {doc.visibility}
                </span>
              </div>
              <p className="mt-1 text-sm text-ink/60">
                {doc.contentType} · {doc.sizeBytes} bytes
                {doc.caseId ? ` · case ${doc.caseId}` : ''}
              </p>
              <a
                className="mt-2 inline-block text-sm text-ink underline"
                href={`/api/v1/knowledge/documents/${doc.id}/content`}
              >
                Download
              </a>
            </li>
          ))}
        </ul>
      </section>
    </div>
  );
}
