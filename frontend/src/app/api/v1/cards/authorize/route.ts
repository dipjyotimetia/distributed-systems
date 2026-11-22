import { NextRequest } from 'next/server';
import { call } from '@/lib/grpc/banking';
import { authorization, grpcResponse } from '@/lib/grpc/http';

export async function POST(request: NextRequest) {
  const body = await request.json();
  return grpcResponse(
    call('cards', 'Authorize', authorization(request), {
      cardToken: body.cardToken,
      idempotencyKey: body.idempotencyKey,
      amount: body.amount,
      merchant: body.merchant,
    }),
  );
}
