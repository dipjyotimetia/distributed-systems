import { NextRequest, NextResponse } from 'next/server';
import { forwardFinancialRequest, uuidPath } from '@/lib/api/financial-proxy';

export async function POST(request: NextRequest, context: { params: Promise<{ movementPath?: string[] }> }) {
  const { movementPath = [] } = await context.params;
  if (movementPath.length) return new NextResponse(null, { status: 404 });
  return forwardFinancialRequest(request, '/api/v1/movements');
}

export async function GET(request: NextRequest, context: { params: Promise<{ movementPath?: string[] }> }) {
  const { movementPath = [] } = await context.params;
  if (movementPath.length !== 1) return NextResponse.json({ code: 'NOT_FOUND' }, { status: 404 });
  if (!uuidPath.test(movementPath[0])) return NextResponse.json({ code: 'INVALID_INPUT' }, { status: 400 });
  return forwardFinancialRequest(request, '/api/v1/movements/' + movementPath[0]);
}
