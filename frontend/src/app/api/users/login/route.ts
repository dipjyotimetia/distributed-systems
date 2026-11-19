import { NextRequest, NextResponse } from 'next/server';
import { sessionCookieName, sessionCookieOptions } from '@/lib/auth/session';

// Force Node.js runtime for OpenTelemetry compatibility
export const runtime = 'nodejs';

const API_GATEWAY_URL = process.env.API_GATEWAY_URL || 'http://localhost:8080';

// Trace propagation utilities
const generateTraceId = (): string => {
  return Array.from({ length: 32 }, () => Math.floor(Math.random() * 16).toString(16)).join('');
};

const generateSpanId = (): string => {
  return Array.from({ length: 16 }, () => Math.floor(Math.random() * 16).toString(16)).join('');
};

const createTraceParent = (): string => {
  const traceId = generateTraceId();
  const spanId = generateSpanId();
  return `00-${traceId}-${spanId}-01`;
};

export async function POST(request: NextRequest) {
  try {
    const body = await request.json();
    
    const response = await fetch(`${API_GATEWAY_URL}/api/users/login`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'traceparent': createTraceParent(),
      },
      body: JSON.stringify(body),
    });

    const data = await response.json();
    
    if (response.ok && request.headers.get('x-banking-session') === 'cookie') {
      const { token, ...profile } = data;
      if (typeof token !== 'string' || !token) {
        return NextResponse.json({ message: 'Missing session token' }, { status: 502 });
      }
      const session = NextResponse.json(profile, { headers: { 'Cache-Control': 'no-store' } });
      session.cookies.set(sessionCookieName, token, sessionCookieOptions(request));
      return session;
    }
    return NextResponse.json(data, { status: response.status, headers: { 'Cache-Control': 'no-store' } });
  } catch (error) {
    console.error('Login proxy error:', error);
    return NextResponse.json(
      { message: 'Internal server error' },
      { status: 500 }
    );
  }
}