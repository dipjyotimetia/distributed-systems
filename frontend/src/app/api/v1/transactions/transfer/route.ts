import { NextRequest, NextResponse } from 'next/server';
import { withTraceparent } from '@/lib/api/trace-proxy';

// Force Node.js runtime for OpenTelemetry compatibility
export const runtime = 'nodejs';

const API_GATEWAY_URL = process.env.API_GATEWAY_URL || 'http://localhost:8080';

export async function POST(request: NextRequest) {
  let proxy: ReturnType<typeof withTraceparent> | undefined;
  try {
    const authHeader = request.headers.get('authorization');
    const idempotencyKey = request.headers.get('idempotency-key');
    const body = await request.json();

    const headers: Record<string, string> = {
      'Content-Type': 'application/json',
      ...(authHeader && { Authorization: authHeader }),
      ...(idempotencyKey && { 'Idempotency-Key': idempotencyKey }),
    };
    proxy = withTraceparent(request, headers, 'frontend.proxy');
    const response = await fetch(
      `${API_GATEWAY_URL}/api/v1/transactions/transfer`,
      {
        method: 'POST',
        headers,
        body: JSON.stringify(body),
      },
    );
    proxy.end(response.status);

    const contentType = response.headers.get('content-type');
    return new NextResponse(response.body, {
      status: response.status,
      headers: contentType ? { 'Content-Type': contentType } : undefined,
    });
  } catch (error) {
    proxy?.end(undefined, error);
    console.error('Transfer transaction proxy error:', error);
    return NextResponse.json(
      { message: 'Internal server error' },
      { status: 500 },
    );
  }
}
