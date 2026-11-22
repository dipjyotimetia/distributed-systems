import { NextRequest, NextResponse } from 'next/server';
import { tracedFetch } from '@/lib/api/trace-proxy';

export const runtime = 'nodejs';

const AI_SERVICE_URL = process.env.AI_SERVICE_URL || 'http://banking-ai:8080';

export async function GET(request: NextRequest) {
  try {
    const res = await tracedFetch(
      request,
      `${AI_SERVICE_URL}/api/v1/providers`,
    );
    const data = await res.json();
    if (!res.ok || !Array.isArray(data)) {
      return NextResponse.json(
        { error: 'AI providers unavailable' },
        { status: res.ok ? 502 : res.status || 503 },
      );
    }
    return NextResponse.json(data);
  } catch {
    return NextResponse.json(
      { error: 'AI providers unavailable' },
      { status: 503 },
    );
  }
}

export async function POST(request: NextRequest) {
  try {
    const body = await request.json();

    if (!body.message || typeof body.message !== 'string') {
      return NextResponse.json(
        { error: 'Message is required' },
        { status: 400 },
      );
    }

    const mode = body.mode === 'agent' ? 'agent' : 'fanout';
    const upstream =
      mode === 'agent'
        ? `${AI_SERVICE_URL}/api/v1/agent/chat`
        : `${AI_SERVICE_URL}/api/v1/chat`;

    const headers: Record<string, string> = {
      'Content-Type': 'application/json',
    };
    const authorization = request.headers.get('authorization');
    if (authorization) {
      headers.Authorization = authorization;
    }

    const res = await tracedFetch(request, upstream, {
      method: 'POST',
      headers,
      body: JSON.stringify(body),
    });

    const contentType = res.headers.get('content-type') || '';
    if (!contentType.includes('application/json')) {
      const text = await res.text();
      return NextResponse.json(
        { error: text || 'AI service error' },
        { status: res.status || 503 },
      );
    }

    const data = await res.json();
    if (!res.ok) {
      return NextResponse.json(data, { status: res.status });
    }
    return NextResponse.json(data);
  } catch (error) {
    console.error('AI service proxy error:', error);
    return NextResponse.json(
      { error: 'AI service unavailable' },
      { status: 503 },
    );
  }
}
