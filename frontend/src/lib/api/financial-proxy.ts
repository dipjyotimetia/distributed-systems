import { NextRequest, NextResponse } from 'next/server';
import { withTraceparent } from '@/lib/api/trace-proxy';

const gateway = process.env.API_GATEWAY_URL || 'http://localhost:8080';
const bodyLimit = 4096;

export const uuidPath =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export async function forwardFinancialRequest(
  request: NextRequest,
  path: string,
) {
  const authorization = request.headers.get('authorization');
  if (!authorization)
    return NextResponse.json({ code: 'UNAUTHENTICATED' }, { status: 401 });
  const headers = new Headers({ authorization });
  const proxy = withTraceparent(request, headers, `proxy ${path}`);

  try {
    let body: Uint8Array<ArrayBuffer> | undefined;
    if (request.method === 'POST') {
      const key = request.headers.get('idempotency-key');
      if (key) headers.set('idempotency-key', key);
      const mediaType = request.headers
        .get('content-type')
        ?.split(';')[0]
        .trim();
      const reader = request.body?.getReader();
      if (mediaType !== 'application/json' || !reader) {
        proxy.end(400);
        return NextResponse.json({ code: 'INVALID_INPUT' }, { status: 400 });
      }
      headers.set('content-type', mediaType);
      body = new Uint8Array(bodyLimit);
      let length = 0;
      for (;;) {
        const { value, done } = await reader.read();
        if (done) break;
        if (length + value.byteLength > bodyLimit) {
          void reader.cancel().catch(() => undefined);
          proxy.end(400);
          return NextResponse.json({ code: 'INVALID_INPUT' }, { status: 400 });
        }
        body.set(value, length);
        length += value.byteLength;
      }
      body = body.subarray(0, length);
    }

    const response = await fetch(gateway + path, {
      method: request.method,
      headers,
      body,
      redirect: 'manual',
      cache: 'no-store',
      signal: AbortSignal.timeout(10000),
    });
    proxy.end(response.status);
    const output = new Headers({ 'cache-control': 'no-store' });
    for (const header of ['content-type', 'content-disposition']) {
      const value = response.headers.get(header);
      if (value) output.set(header, value);
    }
    const location = response.headers.get('location');
    if (location?.startsWith(path + '/')) output.set('location', location);
    return new NextResponse(response.body, {
      status: response.status,
      headers: output,
    });
  } catch (error) {
    proxy.end(undefined, error);
    return NextResponse.json({ code: 'UPSTREAM_UNAVAILABLE' }, { status: 502 });
  }
}
