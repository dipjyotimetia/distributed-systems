import { NextRequest, NextResponse } from 'next/server';
import { withTraceparent } from '@/lib/api/trace-proxy';

const gateway = process.env.API_GATEWAY_URL || 'http://localhost:8080';

export async function GET(request: NextRequest) {
  let proxy: ReturnType<typeof withTraceparent> | undefined;
  if (process.env.OUTBOUND_PAYMENTS_ENABLED !== 'true')
    return new NextResponse(null, { status: 404 });
  const authorization = request.headers.get('authorization');
  if (!authorization) return new NextResponse(null, { status: 401 });
  const offset = request.nextUrl.searchParams.get('offset') ?? '0';
  if (!/^\d{1,9}$/.test(offset)) return new NextResponse(null, { status: 400 });

  const headers = new Headers({ authorization });
  proxy = withTraceparent(request, headers, 'frontend.proxy');
  try {
    const response = await fetch(
      gateway + '/api/v1/operations/payments/reconciliation?offset=' + offset,
      {
        headers,
        cache: 'no-store',
        redirect: 'manual',
        signal: AbortSignal.timeout(10000),
      },
    );
    proxy.end(response.status);
    const outgoing = new Headers({ 'cache-control': 'no-store' });
    const contentType = response.headers.get('content-type');
    if (contentType) outgoing.set('content-type', contentType);
    return new NextResponse(response.body, {
      status: response.status,
      headers: outgoing,
    });
  } catch (error) {
    proxy?.end(undefined, error);
    return NextResponse.json(
      { message: 'The reconciliation queue is temporarily unavailable' },
      { status: 502 },
    );
  }
}
