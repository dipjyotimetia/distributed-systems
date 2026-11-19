import { NextRequest, NextResponse } from 'next/server';
import { sessionCookieName } from '@/lib/auth/session';

// Simple logging function that works in Edge Runtime
const logApiRequest = (method: string, url: string, userAgent?: string, ip?: string) => {
  const logEntry = {
    timestamp: new Date().toISOString(),
    level: 'info',
    service: 'frontend',
    message: 'API Request',
    type: 'api_request',
    method,
    url,
    userAgent,
    ip
  };
  
  // Output as structured JSON that will appear in pod logs
  console.log(JSON.stringify(logEntry));
};

export function middleware(request: NextRequest) {
  // Only log API requests
  if (request.nextUrl.pathname.startsWith('/api/')) {
    const method = request.method;
    const url = request.nextUrl.pathname + request.nextUrl.search;
    const userAgent = request.headers.get('user-agent') || undefined;
    const forwarded = request.headers.get('x-forwarded-for') || undefined;

    // Use simple logging instead of winston logger
    logApiRequest(method, url, userAgent, forwarded);
  }

  const token = request.cookies.get(sessionCookieName)?.value;
  if (token && !request.headers.has('authorization')) {
    const headers = new Headers(request.headers);
    headers.set('authorization', `Bearer ${token}`);
    return NextResponse.next({ request: { headers } });
  }
  return NextResponse.next();
}

export const config = {
  matcher: [
    // Match all API routes
    '/api/:path*',
  ],
};