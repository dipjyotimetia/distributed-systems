import { NextRequest, NextResponse } from 'next/server';
import { sessionCookieName, sessionCookieOptions } from '@/lib/auth/session';

export function POST(request: NextRequest) {
  const response = new NextResponse(null, { status: 204 });
  response.cookies.set(sessionCookieName, '', {
    ...sessionCookieOptions(request),
    maxAge: 0,
  });
  return response;
}
