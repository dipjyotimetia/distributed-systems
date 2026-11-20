import { NextRequest } from 'next/server';
import { call } from '@/lib/grpc/banking';
import { authorization, grpcResponse } from '@/lib/grpc/http';

export async function GET(request: NextRequest) {
  return grpcResponse(call('kyc', 'GetStatus', authorization(request), {}));
}

export async function POST(request: NextRequest) {
  const body = await request.json();
  return grpcResponse(call('kyc', 'Apply', authorization(request), {
    fullName: body.fullName, documentReference: body.documentReference,
  }));
}
