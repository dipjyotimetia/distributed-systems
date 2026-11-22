import { NextRequest, NextResponse } from 'next/server';
import { withTraceparent } from '@/lib/api/trace-proxy';

const gateway = process.env.API_GATEWAY_URL || 'http://localhost:8080';
const maxBodyBytes = 16 * 1024;

async function readBody(stream: ReadableStream<Uint8Array> | null) {
  const buffer = new Uint8Array(maxBodyBytes);
  if (!stream) return buffer.subarray(0, 0);
  const reader = stream.getReader();

  let offset = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) return buffer.subarray(0, offset);
    if (offset + value.byteLength > buffer.byteLength) {
      void reader.cancel().catch(() => undefined);
      return null;
    }
    buffer.set(value, offset);
    offset += value.byteLength;
  }
}

export function disputeProxy(operations: boolean) {
  return async (
    request: NextRequest,
    { params }: { params: Promise<{ casePath?: string[] }> },
  ) => {
    const { casePath = [] } = await params;
    const [id, action] = casePath;
    const validID = /^[1-9][0-9]*$/.test(id ?? '');
    const read = request.method === 'GET' || request.method === 'HEAD';
    const evidenceRequest =
      validID && action === 'evidence-requests' && casePath.length === 2;
    const evidenceResponse =
      validID &&
      action === 'evidence-requests' &&
      /^[1-9][0-9]*$/.test(casePath[2] ?? '') &&
      casePath[3] === 'response' &&
      casePath.length === 4;
    const validPath = operations
      ? read
        ? casePath.length === 0
        : evidenceRequest ||
          (validID && action === 'resolve' && casePath.length === 2)
      : read
        ? casePath.length === 0 || (validID && casePath.length === 1)
        : casePath.length === 0 || evidenceResponse;
    if (!validPath) {
      return NextResponse.json(
        { message: 'Dispute route not found' },
        { status: 404 },
      );
    }

    const prefix = operations
      ? '/api/v1/operations/disputes'
      : '/api/v1/disputes';
    const suffix = casePath.length ? '/' + casePath.join('/') : '';
    const headers = new Headers();
    const auth = request.headers.get('authorization');
    if (auth) headers.set('authorization', auth);
    if (!read) {
      const contentType = request.headers
        .get('content-type')
        ?.split(';')[0]
        .trim()
        .toLowerCase();
      if (contentType !== 'application/json') {
        return NextResponse.json(
          { message: 'JSON content is required' },
          { status: 415 },
        );
      }
      if (Number(request.headers.get('content-length')) > maxBodyBytes) {
        return NextResponse.json(
          { message: 'Dispute request is too large' },
          { status: 413 },
        );
      }
      headers.set('content-type', 'application/json');
      const key = request.headers.get('idempotency-key');
      if (key) headers.set('idempotency-key', key);
    }

    const proxy = withTraceparent(request, headers, `proxy ${prefix}${suffix}`);
    try {
      const body = read ? undefined : await readBody(request.body);
      if (body === null) {
        proxy.end(413);
        return NextResponse.json(
          { message: 'Dispute request is too large' },
          { status: 413 },
        );
      }
      const response = await fetch(
        gateway + prefix + suffix + request.nextUrl.search,
        {
          method: request.method,
          headers,
          body,
          cache: 'no-store',
          redirect: 'manual',
          signal: AbortSignal.timeout(15000),
        },
      );
      proxy.end(response.status);
      const responseHeaders = new Headers({ 'cache-control': 'no-store' });
      const contentType = response.headers.get('content-type');
      if (contentType) responseHeaders.set('content-type', contentType);
      return new NextResponse(
        request.method === 'HEAD' ? null : response.body,
        { status: response.status, headers: responseHeaders },
      );
    } catch (error) {
      proxy.end(undefined, error);
      return NextResponse.json(
        { message: 'Disputes are temporarily unavailable' },
        { status: 502 },
      );
    }
  };
}
