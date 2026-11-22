import { NextRequest, NextResponse } from 'next/server';
import { withTraceparent } from '@/lib/api/trace-proxy';

const gateway = process.env.API_GATEWAY_URL || 'http://localhost:8080';
const paymentId =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const maxRequestBytes = 2048;

async function forward(
  request: NextRequest,
  { params }: { params: Promise<{ paymentPath?: string[] }> },
) {
  const { paymentPath = [] } = await params;
  const write = request.method === 'POST';
  const readOne =
    !write && paymentPath.length === 1 && paymentId.test(paymentPath[0]);
  const readCollection = !write && paymentPath.length === 0;
  if (
    process.env.OUTBOUND_PAYMENTS_ENABLED !== 'true' ||
    (write ? paymentPath.length !== 0 : !readOne && !readCollection)
  ) {
    return new NextResponse(null, { status: 404 });
  }

  const headers = new Headers();
  const authorization = request.headers.get('authorization');
  if (!authorization) return new NextResponse(null, { status: 401 });
  headers.set('authorization', authorization);
  proxy = withTraceparent(request, headers, 'frontend.proxy');

  try {
    let body: Uint8Array<ArrayBuffer> | undefined;
    if (write) {
      if (
        request.headers.get('content-type')?.split(';')[0].trim() !==
        'application/json'
      ) {
        return new NextResponse(null, { status: 415 });
      }
      const key = request.headers.get('idempotency-key');
      if (!key) return new NextResponse(null, { status: 400 });
      headers.set('idempotency-key', key);
      headers.set('content-type', 'application/json');
      const reader = request.body?.getReader();
      if (!reader) return new NextResponse(null, { status: 400 });
      body = new Uint8Array(maxRequestBytes);
      let filled = 0;
      for (;;) {
        const { done, value } = await reader.read();
        if (done) break;
        if (filled + value.byteLength > maxRequestBytes) {
          void reader.cancel().catch(() => undefined);
          return new NextResponse(null, { status: 413 });
        }
        body.set(value, filled);
        filled += value.byteLength;
      }
      body = body.subarray(0, filled);
    }

    const suffix = readOne ? '/' + paymentPath[0] : '';
    const response = await fetch(gateway + '/api/v1/payments' + suffix, {
      method: request.method,
      headers,
      body,
      redirect: 'manual',
      cache: 'no-store',
      signal: AbortSignal.timeout(10000),
    });
    proxy.end(response.status);
    const resultHeaders = new Headers({ 'cache-control': 'no-store' });
    const contentType = response.headers.get('content-type');
    if (contentType) resultHeaders.set('content-type', contentType);
    const location = response.headers.get('location');
    if (location?.startsWith('/api/v1/payments/'))
      resultHeaders.set('location', location);
    return new NextResponse(response.body, {
      status: response.status,
      headers: resultHeaders,
    });
  } catch (error) {
    proxy?.end(undefined, error);
    return NextResponse.json(
      { message: 'Payments are temporarily unavailable' },
      { status: 502 },
    );
  }
}

export const POST = forward;
export const GET = forward;
