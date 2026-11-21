import { NextRequest, NextResponse } from 'next/server';

const gateway = process.env.API_GATEWAY_URL || 'http://localhost:8080';
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

async function proxy(request: NextRequest, { params }: { params: Promise<{ id: string; jobPath?: string[] }> }) {
  const { id, jobPath = [] } = await params;
  const create = request.method === 'POST';
  if (!/^[1-9][0-9]*$/.test(id) || (create && jobPath.length !== 0) ||
      (jobPath.length > 0 && !(uuid.test(jobPath[0]) &&
        (jobPath.length === 1 || (jobPath.length === 2 && jobPath[1] === 'download'))))) {
    return NextResponse.json({ message: 'Statement job route not found' }, { status: 404 });
  }
  const headers = new Headers();
  const auth = request.headers.get('authorization');
  const key = request.headers.get('idempotency-key');
  if (auth) headers.set('authorization', auth);
  if (create && key) headers.set('idempotency-key', key);

  const suffix = jobPath.length ? '/' + jobPath.join('/') : '';
  try {
    const response = await fetch(gateway + '/api/v1/accounts/' + id + '/statement-jobs' + suffix + request.nextUrl.search, {
      method: request.method,
      headers,
      cache: 'no-store',
      redirect: 'manual',
      signal: AbortSignal.timeout(15000),
    });
    const outgoing = new Headers({ 'cache-control': 'no-store' });
    for (const name of ['content-type', 'content-disposition', 'location']) {
      const value = response.headers.get(name);
      if (value) outgoing.set(name, value);
    }
    return new NextResponse(request.method === 'HEAD' ? null : response.body, { status: response.status, headers: outgoing });
  } catch {
    return NextResponse.json({ message: 'Statement exports are temporarily unavailable' }, { status: 502 });
  }
}

export const GET = proxy;
export const HEAD = proxy;
export const POST = proxy;
