import { NextRequest, NextResponse } from 'next/server';
import * as grpc from '@grpc/grpc-js';

export function grpcResponse(result: Promise<Record<string, unknown>>) {
  return result.then(
    (body) => NextResponse.json(body),
    (error: grpc.ServiceError) => NextResponse.json(
      { message: error.details || 'request failed' },
      { status: error.code === grpc.status.UNAUTHENTICATED ? 401 : error.code === grpc.status.PERMISSION_DENIED ? 403 : error.code === grpc.status.INVALID_ARGUMENT ? 400 : 502 },
    ),
  );
}

export function authorization(request: NextRequest) {
  return request.headers.get('authorization');
}
