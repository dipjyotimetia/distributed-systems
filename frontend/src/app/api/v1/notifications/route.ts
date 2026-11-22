import { NextRequest, NextResponse } from 'next/server';
import { tracedFetch } from '@/lib/api/trace-proxy';

const notifications =
  process.env.NOTIFICATION_SERVICE_URL || 'http://notification-service:8080';

export async function GET(request: NextRequest) {
  const authorization = request.headers.get('authorization');
  if (!authorization) {
    return NextResponse.json(
      { message: 'Sign in to continue' },
      { status: 401 },
    );
  }
  try {
    const response = await tracedFetch(
      request,
      `${notifications}/api/v1/notifications/me`,
      {
        headers: { authorization },
        cache: 'no-store',
        signal: AbortSignal.timeout(10000),
      },
    );
    return new NextResponse(await response.text(), {
      status: response.status,
      headers: {
        'content-type':
          response.headers.get('content-type') || 'application/json',
        'cache-control': 'no-store',
      },
    });
  } catch {
    return NextResponse.json(
      { message: 'Notifications are temporarily unavailable' },
      { status: 502 },
    );
  }
}
