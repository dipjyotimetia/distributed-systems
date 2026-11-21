import { NextRequest, NextResponse } from 'next/server';

export const runtime = 'nodejs';

const AI_SERVICE_URL = process.env.AI_SERVICE_URL || 'http://banking-ai:8080';

export async function GET() {
  try {
    const res = await fetch(`${AI_SERVICE_URL}/api/v1/providers`);
    const data = await res.json();
    return NextResponse.json(data);
  } catch {
    return NextResponse.json({ providers: [] });
  }
}

export async function POST(request: NextRequest) {
  try {
    const body = await request.json();

    if (!body.message || typeof body.message !== 'string') {
      return NextResponse.json({ error: 'Message is required' }, { status: 400 });
    }

    const mode = body.mode === 'agent' ? 'agent' : 'fanout';
    const upstream =
      mode === 'agent' ? `${AI_SERVICE_URL}/api/v1/agent/chat` : `${AI_SERVICE_URL}/api/v1/chat`;

    const headers: Record<string, string> = { 'Content-Type': 'application/json' };
    const authorization = request.headers.get('authorization');
    if (authorization) {
      headers.Authorization = authorization;
    }

    const res = await fetch(upstream, {
      method: 'POST',
      headers,
      body: JSON.stringify(body),
    });

    const contentType = res.headers.get('content-type') || '';
    if (!contentType.includes('application/json')) {
      const text = await res.text();
      return NextResponse.json(
        { error: text || 'AI service error' },
        { status: res.status || 503 }
      );
    }

    const data = await res.json();
    if (!res.ok) {
      return NextResponse.json(data, { status: res.status });
    }
    return NextResponse.json(data);
  } catch (error) {
    console.error('AI service proxy error:', error);
    return NextResponse.json({ error: 'AI service unavailable' }, { status: 503 });
  }
}
