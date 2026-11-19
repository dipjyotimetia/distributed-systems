import { NextRequest } from 'next/server';

export const sessionCookieName = process.env.NEXT_PUBLIC_JWT_COOKIE_NAME || 'auth_token';

export function sessionCookieOptions(request: NextRequest) {
  return {
    httpOnly: true,
    sameSite: 'lax' as const,
    secure: request.nextUrl.protocol === 'https:' || request.headers.get('x-forwarded-proto') === 'https',
    path: '/',
  };
}
