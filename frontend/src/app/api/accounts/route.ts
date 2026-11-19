import { NextRequest, NextResponse } from 'next/server';
import { context, propagation, trace } from '@opentelemetry/api';

// Force Node.js runtime for OpenTelemetry compatibility
export const runtime = 'nodejs';

const API_GATEWAY_URL = process.env.API_GATEWAY_URL || 'http://localhost:8080';

// Trace propagation utilities
const generateTraceId = (): string => {
  return Array.from({ length: 32 }, () => Math.floor(Math.random() * 16).toString(16)).join('');
};

const generateSpanId = (): string => {
  return Array.from({ length: 16 }, () => Math.floor(Math.random() * 16).toString(16)).join('');
};

const createTraceParent = (): string => {
  const traceId = generateTraceId();
  const spanId = generateSpanId();
  return `00-${traceId}-${spanId}-01`;
};

export async function GET(request: NextRequest) {
  const parent = propagation.extract(context.active(), Object.fromEntries(request.headers));
  const span = trace.getTracer('frontend').startSpan('proxy accounts list', {}, parent);
  try {
    const authHeader = request.headers.get('authorization');
    const { searchParams } = new URL(request.url);
    const traceHeaders: Record<string, string> = {};
    propagation.inject(trace.setSpan(parent, span), traceHeaders);

    const response = await fetch(`${API_GATEWAY_URL}/api/accounts?${searchParams}`, {
      method: 'GET',
      headers: {
        'Content-Type': 'application/json',
        ...(authHeader && { 'Authorization': authHeader }),
        traceparent: createTraceParent(),
        ...traceHeaders,
      },
    });

    const contentType = response.headers.get('content-type');
    return new NextResponse(response.body, {
      status: response.status,
      headers: contentType ? { 'Content-Type': contentType } : undefined,
    });
  } catch (error) {
    console.error('Accounts proxy error:', error);
    return NextResponse.json(
      { message: 'Internal server error' },
      { status: 500 }
    );
  } finally {
    span.end();
  }
}

export async function POST(request: NextRequest) {
  try {
    const authHeader = request.headers.get('authorization');
    const body = await request.json();
    
    const response = await fetch(`${API_GATEWAY_URL}/api/accounts`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        ...(authHeader && { 'Authorization': authHeader }),
        'traceparent': createTraceParent(),
      },
      body: JSON.stringify(body),
    });

    const contentType = response.headers.get('content-type');
    return new NextResponse(response.body, {
      status: response.status,
      headers: contentType ? { 'Content-Type': contentType } : undefined,
    });
  } catch (error) {
    console.error('Create account proxy error:', error);
    return NextResponse.json(
      { message: 'Internal server error' },
      { status: 500 }
    );
  }
}
