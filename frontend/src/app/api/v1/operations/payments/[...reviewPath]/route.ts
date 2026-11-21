import { NextRequest, NextResponse } from 'next/server';
import { forwardFinancialRequest, uuidPath } from '@/lib/api/financial-proxy';

export async function GET(request: NextRequest, context: { params: Promise<{ reviewPath: string[] }> }) {
  const { reviewPath } = await context.params;
  if (process.env.OUTBOUND_PAYMENTS_ENABLED !== 'true' || reviewPath.join('/') !== 'review') {
    return new NextResponse(null, { status: 404 });
  }
  const offset = request.nextUrl.searchParams.get('offset');
  if (offset !== null && !/^[0-9]{1,9}$/.test(offset)) {
    return NextResponse.json({ code: 'INVALID_INPUT' }, { status: 400 });
  }
  return forwardFinancialRequest(request, '/api/v1/operations/payments/review' + (offset ? '?offset=' + offset : ''));
}

export async function POST(request: NextRequest, context: { params: Promise<{ reviewPath: string[] }> }) {
  const { reviewPath: [id, action, ...rest] } = await context.params;
  if (process.env.OUTBOUND_PAYMENTS_ENABLED !== 'true' || !uuidPath.test(id ?? '') || action !== 'resolutions' || rest.length) {
    return new NextResponse(null, { status: 404 });
  }
  return forwardFinancialRequest(request, `/api/v1/operations/payments/${id}/resolutions`);
}
