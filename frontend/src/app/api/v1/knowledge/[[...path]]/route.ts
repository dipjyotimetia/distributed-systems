import { NextRequest, NextResponse } from 'next/server';
import { withTraceparent } from '@/lib/api/trace-proxy';

export const runtime = 'nodejs';

const gateway = process.env.API_GATEWAY_URL || 'http://localhost:8080';
const knowledgeDirect =
  process.env.KNOWLEDGE_SERVICE_URL || 'http://knowledge-service:8080';

async function forward(
  request: NextRequest,
  { params }: { params: Promise<{ path?: string[] }> },
) {
  const { path = [] } = await params;
  const suffix = path.length ? `/${path.join('/')}` : '';
  const upstreamBase =
    process.env.KNOWLEDGE_VIA_GATEWAY === 'false' ? knowledgeDirect : gateway;
  const target = `${upstreamBase}/api/v1/knowledge${suffix}${request.nextUrl.search}`;

  const headers = new Headers();
  const authorization = request.headers.get('authorization');
  if (!authorization) {
    return NextResponse.json({ error: 'Unauthorized' }, { status: 401 });
  }
  headers.set('authorization', authorization);

  const contentType = request.headers.get('content-type');
  if (contentType) {
    headers.set('content-type', contentType);
  }

  const proxy = withTraceparent(request, headers, 'frontend.proxy.knowledge');

  try {
    const init: RequestInit = {
      method: request.method,
      headers,
      redirect: 'manual',
      cache: 'no-store',
      signal: AbortSignal.timeout(30000),
    };
    if (request.method !== 'GET' && request.method !== 'HEAD') {
      init.body = await request.arrayBuffer();
    }
    const response = await fetch(target, init);
    proxy.end(response.status);

    const resultHeaders = new Headers({ 'cache-control': 'no-store' });
    const upstreamType = response.headers.get('content-type');
    if (upstreamType) {
      resultHeaders.set('content-type', upstreamType);
    }
    const disposition = response.headers.get('content-disposition');
    if (disposition) {
      resultHeaders.set('content-disposition', disposition);
    }
    return new NextResponse(response.body, {
      status: response.status,
      headers: resultHeaders,
    });
  } catch (error) {
    console.error('knowledge proxy error', error);
    proxy.end(503);
    return NextResponse.json({ error: 'Knowledge service unavailable' }, { status: 503 });
  }
}

export const GET = forward;
export const POST = forward;
