'use client';

import React, { useState, useRef, useEffect } from 'react';
import ProtectedRoute from '@/components/auth/ProtectedRoute';
import AuthenticatedLayout from '@/components/layout/AuthenticatedLayout';
import { TokenManager } from '@/lib/auth/token';

interface ProviderResult {
  provider: string;
  name: string;
  model: string;
  message: string;
  error?: string;
  durationMs: number;
}

interface ChatMessage {
  role: 'user' | 'assistant';
  content: string;
  results?: ProviderResult[];
  agent?: boolean;
  blocked?: boolean;
  conversationClaim?: string;
}

const AIAssistantPage: React.FC = () => {
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [input, setInput] = useState('');
  const [isLoading, setIsLoading] = useState(false);
  const [mode, setMode] = useState<'fanout' | 'agent'>('fanout');
  const [conversationClaim, setConversationClaim] = useState<string | null>(null);
  const messagesEndRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages]);

  const sendMessage = async () => {
    const trimmed = input.trim();
    if (!trimmed || isLoading) return;

    const userMessage: ChatMessage = { role: 'user', content: trimmed };
    setMessages(prev => [...prev, userMessage]);
    setInput('');
    setIsLoading(true);

    try {
      const headers: Record<string, string> = {
        'Content-Type': 'application/json',
      };
      const token = TokenManager.getToken();
      if (token) {
        headers.Authorization = `Bearer ${token}`;
      }

      const res = await fetch('/api/ai/chat', {
        method: 'POST',
        headers,
        body: JSON.stringify({
          message: trimmed,
          mode,
          ...(mode === 'agent' && conversationClaim
            ? { conversationClaim }
            : {}),
        }),
      });

      const data = await res.json();

      if (data.results) {
        const assistantMessage: ChatMessage = {
          role: 'assistant',
          content: '',
          results: data.results,
        };
        setMessages(prev => [...prev, assistantMessage]);
      } else if (typeof data.reply === 'string') {
        if (typeof data.conversationClaim === 'string' && data.conversationClaim) {
          setConversationClaim(data.conversationClaim);
        }
        setMessages(prev => [
          ...prev,
          {
            role: 'assistant',
            content: data.reply,
            agent: true,
            blocked: Boolean(data.blocked),
            conversationClaim:
              typeof data.conversationClaim === 'string' ? data.conversationClaim : undefined,
          },
        ]);
      } else {
        setMessages(prev => [
          ...prev,
          { role: 'assistant', content: data.error || 'Something went wrong.' },
        ]);
      }
    } catch {
      setMessages(prev => [
        ...prev,
        { role: 'assistant', content: 'Unable to reach the AI service. Please try again later.' },
      ]);
    } finally {
      setIsLoading(false);
    }
  };

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      sendMessage();
    }
  };

  return (
    <ProtectedRoute requireAuth={true}>
      <AuthenticatedLayout>
        <div className="mx-auto flex max-w-5xl flex-col" style={{ height: 'calc(100vh - 7rem)' }}>
          <div className="mb-4">
            <p className="text-xs font-medium uppercase tracking-[0.18em] text-brass">Assistant</p>
            <h1 className="mt-1 text-3xl font-semibold tracking-tight text-ink">AI Banking Assistant</h1>
            <p className="text-sm text-ink/70">
              {mode === 'agent'
                ? 'Durable Temporal agent with read-only tools and hard-rule guardrails'
                : 'Ask a question — all configured LLM providers respond simultaneously'}
            </p>
            <div className="mt-3 flex gap-2">
              <button
                type="button"
                onClick={() => {
                  setMode('fanout');
                  setConversationClaim(null);
                }}
                className={`rounded-full px-3 py-1 text-xs font-medium ${
                  mode === 'fanout' ? 'bg-ink text-canvas' : 'border border-line text-ink/70'
                }`}
              >
                Multi-provider
              </button>
              <button
                type="button"
                onClick={() => setMode('agent')}
                className={`rounded-full px-3 py-1 text-xs font-medium ${
                  mode === 'agent' ? 'bg-ink text-canvas' : 'border border-line text-ink/70'
                }`}
              >
                Durable agent
              </button>
            </div>
          </div>

          <div className="mb-4 flex-1 space-y-4 overflow-y-auto rounded-3xl border border-line bg-surface p-4">
            {messages.length === 0 && (
              <div className="py-12 text-center text-ink/60">
                <p className="mb-2 text-lg font-medium text-ink">No messages yet</p>
                <p className="text-sm">Try asking about your account balance or recent transactions.</p>
              </div>
            )}

            {messages.map((msg, i) => (
              <div key={i}>
                {msg.role === 'user' ? (
                  <div className="flex justify-end">
                    <div className="max-w-[75%] whitespace-pre-wrap rounded-2xl bg-ink px-4 py-2 text-sm text-canvas">
                      {msg.content}
                    </div>
                  </div>
                ) : msg.results ? (
                  <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-3">
                    {msg.results.map((r, j) => (
                        <div key={j} className="rounded-2xl border border-line bg-[#f6f1e6] p-3 text-sm text-ink">
                          <div className="mb-2 flex items-center justify-between">
                            <span className="inline-block rounded-full bg-surface px-2 py-0.5 text-xs font-medium text-brass">
                              {r.name}
                            </span>
                            <span className="text-xs text-ink/50">{r.durationMs}ms</span>
                          </div>
                          {r.error ? (
                            <div className="text-xs text-red-700">
                              <span className="font-medium">Error:</span> {r.error}
                            </div>
                          ) : (
                            <div className="whitespace-pre-wrap text-ink">{r.message}</div>
                          )}
                          <div className="mt-2 text-xs text-ink/50">{r.model}</div>
                        </div>
                    ))}
                  </div>
                ) : (
                  <div className="flex justify-start">
                    <div className="max-w-[75%] whitespace-pre-wrap rounded-2xl border border-line bg-surface px-4 py-2 text-sm text-ink">
                      {msg.content}
                    </div>
                  </div>
                )}
              </div>
            ))}

            {isLoading && (
              <div className="flex justify-start">
                <div className="rounded-2xl border border-line bg-[#f6f1e6] px-4 py-2 text-sm text-ink/70">
                  {mode === 'agent' ? 'Running durable agent workflow...' : 'Querying all providers...'}
                </div>
              </div>
            )}

            <div ref={messagesEndRef} />
          </div>

          <div>
            <label htmlFor="ai-message" className="mb-2 block text-sm font-medium text-ink">Message</label>
            <div className="flex gap-2">
            <input
              id="ai-message"
              type="text"
              value={input}
              onChange={e => setInput(e.target.value)}
              onKeyDown={handleKeyDown}
              placeholder="Ask a question..."
              disabled={isLoading}
              className="flex-1 rounded-full border border-line bg-surface px-4 py-2 text-sm text-ink focus:border-brass focus:outline-none focus:ring-2 focus:ring-brass disabled:bg-[#f6f1e6]"
            />
            <button
              onClick={sendMessage}
              disabled={isLoading || !input.trim()}
              className="rounded-full bg-ink px-6 py-2 text-sm font-medium text-canvas transition-colors hover:bg-[#24362f] disabled:opacity-50"
            >
              Send
            </button>
            </div>
          </div>
        </div>
      </AuthenticatedLayout>
    </ProtectedRoute>
  );
};

export default AIAssistantPage;
