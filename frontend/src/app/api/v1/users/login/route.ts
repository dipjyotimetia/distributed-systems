import { NextRequest, NextResponse } from 'next/server';
import { sessionCookieName, sessionCookieOptions } from '@/lib/auth/session';
import { withTraceparent } from '@/lib/api/trace-proxy';

export const runtime = 'nodejs';

const API_GATEWAY_URL = process.env.API_GATEWAY_URL || 'http://localhost:8080';

export async function POST(request: NextRequest) {
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
  };
  const proxy = withTraceparent(request, headers, 'proxy users login');
  try {
    const body = await request.json();

    const response = await fetch(`${API_GATEWAY_URL}/api/v1/users/login`, {
      method: 'POST',
      headers,
      body: JSON.stringify(body),
    });

    const data = await response.json();
    proxy.end(response.status);

    if (response.ok && request.headers.get('x-banking-session') === 'cookie') {
      const { token, ...profile } = data;
      if (typeof token !== 'string' || !token) {
        return NextResponse.json(
          { message: 'Missing session token' },
          { status: 502 },
        );
      }
      const session = NextResponse.json(profile, {
        headers: { 'Cache-Control': 'no-store' },
      });
      session.cookies.set(
        sessionCookieName,
        token,
        sessionCookieOptions(request),
      );
      return session;
    }
    return NextResponse.json(data, {
      status: response.status,
      headers: { 'Cache-Control': 'no-store' },
    });
  } catch (error) {
    proxy.end(undefined, error);
    console.error('Login proxy error:', error);
    return NextResponse.json(
      { message: 'Internal server error' },
      { status: 500 },
    );
  }
}
