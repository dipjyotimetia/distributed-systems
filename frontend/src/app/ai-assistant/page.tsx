'use client';

import React, { useState, useRef, useEffect } from 'react';
import ProtectedRoute from '@/components/auth/ProtectedRoute';
import AuthenticatedLayout from '@/components/layout/AuthenticatedLayout';

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
}

const PROVIDER_COLORS: Record<string, { bg: string; border: string; badge: string }> = {
  anthropic: { bg: 'bg-orange-50', border: 'border-orange-200', badge: 'bg-orange-100 text-orange-800' },
  openai: { bg: 'bg-green-50', border: 'border-green-200', badge: 'bg-green-100 text-green-800' },
  gemini: { bg: 'bg-blue-50', border: 'border-blue-200', badge: 'bg-blue-100 text-blue-800' },
  xai: { bg: 'bg-purple-50', border: 'border-purple-200', badge: 'bg-purple-100 text-purple-800' },
  openrouter: { bg: 'bg-pink-50', border: 'border-pink-200', badge: 'bg-pink-100 text-pink-800' },
};

const AIAssistantPage: React.FC = () => {
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [input, setInput] = useState('');
  const [isLoading, setIsLoading] = useState(false);
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
      const res = await fetch('/api/ai/chat', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({ message: trimmed }),
      });

      const data = await res.json();

      if (data.results) {
        const assistantMessage: ChatMessage = {
          role: 'assistant',
          content: '',
          results: data.results,
        };
        setMessages(prev => [...prev, assistantMessage]);
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
            <p className="text-sm text-ink/70">Ask a question — all configured LLM providers respond simultaneously</p>
          </div>

          <div className="mb-4 flex-1 space-y-4 overflow-y-auto rounded-3xl border border-line bg-surface p-4">
            {messages.length === 0 && (
              <div className="text-center text-gray-400 py-12">
                <p className="text-lg font-medium mb-2">No messages yet</p>
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
                    {msg.results.map((r, j) => {
                      const colors = PROVIDER_COLORS[r.provider] || { bg: 'bg-gray-50', border: 'border-gray-200', badge: 'bg-gray-100 text-gray-600' };
                      return (
                        <div key={j} className={`rounded-lg border ${colors.border} ${colors.bg} p-3 text-sm`}>
                          <div className="flex items-center justify-between mb-2">
                            <span className={`inline-block text-xs px-2 py-0.5 rounded-full font-medium ${colors.badge}`}>
                              {r.name}
                            </span>
                            <span className="text-xs text-gray-400">{r.durationMs}ms</span>
                          </div>
                          {r.error ? (
                            <div className="text-red-600 text-xs">
                              <span className="font-medium">Error:</span> {r.error}
                            </div>
                          ) : (
                            <div className="text-gray-900 whitespace-pre-wrap">{r.message}</div>
                          )}
                          <div className="mt-2 text-xs text-gray-400">{r.model}</div>
                        </div>
                      );
                    })}
                  </div>
                ) : (
                  <div className="flex justify-start">
                    <div className="max-w-[75%] rounded-lg px-4 py-2 text-sm whitespace-pre-wrap bg-gray-100 text-gray-900">
                      {msg.content}
                    </div>
                  </div>
                )}
              </div>
            ))}

            {isLoading && (
              <div className="flex justify-start">
                <div className="bg-gray-100 rounded-lg px-4 py-2 text-sm text-gray-500">
                  Querying all providers...
                </div>
              </div>
            )}

            <div ref={messagesEndRef} />
          </div>

          <div className="flex gap-2">
            <input
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
      </AuthenticatedLayout>
    </ProtectedRoute>
  );
};

export default AIAssistantPage;
