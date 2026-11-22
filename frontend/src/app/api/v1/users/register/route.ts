import { NextRequest, NextResponse } from 'next/server';
import { withTraceparent } from '@/lib/api/trace-proxy';

// Force Node.js runtime for OpenTelemetry compatibility
export const runtime = 'nodejs';

const API_GATEWAY_URL = process.env.API_GATEWAY_URL || 'http://localhost:8080';

export async function POST(request: NextRequest) {
  let proxy: ReturnType<typeof withTraceparent> | undefined;
  try {
    const body = await request.json();

    const headers: Record<string, string> = {
      'Content-Type': 'application/json',
    };
    proxy = withTraceparent(request, headers, 'frontend.proxy');
    const response = await fetch(`${API_GATEWAY_URL}/api/v1/users/register`, {
      method: 'POST',
      headers,
      body: JSON.stringify(body),
    });
    proxy.end(response.status);

    const data = await response.json();

    return NextResponse.json(data, { status: response.status });
  } catch (error) {
    proxy?.end(undefined, error);
    console.error('Registration proxy error:', error);
    return NextResponse.json(
      { message: 'Internal server error' },
      { status: 500 },
    );
  }
}
