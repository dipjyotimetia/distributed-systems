import { NextRequest, NextResponse } from 'next/server';
import { tracedFetch } from '@/lib/api/trace-proxy';

export const runtime = 'nodejs';

const API_GATEWAY_URL = process.env.API_GATEWAY_URL || 'http://localhost:8080';

export async function POST(
  request: NextRequest,
  { params }: { params: Promise<{ id: string }> },
) {
  try {
    const authHeader = request.headers.get('authorization');
    const resolvedParams = await params;

    const response = await tracedFetch(
      request,
      `${API_GATEWAY_URL}/api/v1/accounts/${resolvedParams.id}/export-statement`,
      {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          ...(authHeader && { Authorization: authHeader }),
        },
      },
    );

    const data = await response.json();
    return NextResponse.json(data, { status: response.status });
  } catch (error) {
    console.error('Export statement proxy error:', error);
    return NextResponse.json(
      { message: 'Internal server error' },
      { status: 500 },
    );
  }
}
