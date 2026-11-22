import { NextRequest, NextResponse } from 'next/server';

export const runtime = 'nodejs';

/**
 * Same-origin OTLP/HTTP proxy for the browser SDK so collectors do not need CORS.
 */
export async function POST(request: NextRequest) {
  const endpoint =
    process.env.OTEL_BROWSER_OTLP_ENDPOINT ||
    process.env.OTEL_EXPORTER_OTLP_ENDPOINT ||
    'http://localhost:4318/v1/traces';
  const url = endpoint.includes('/v1/traces')
    ? endpoint
    : endpoint.replace(/\/$/, '') + '/v1/traces';

  try {
    const body = await request.arrayBuffer();
    const upstream = await fetch(url, {
      method: 'POST',
      headers: {
        'content-type':
          request.headers.get('content-type') || 'application/json',
      },
      body,
      signal: AbortSignal.timeout(10000),
    });
    return new NextResponse(upstream.body, {
      status: upstream.status,
      headers: {
        'content-type':
          upstream.headers.get('content-type') || 'application/json',
      },
    });
  } catch {
    return NextResponse.json({ message: 'Trace export unavailable' }, { status: 502 });
  }
}
