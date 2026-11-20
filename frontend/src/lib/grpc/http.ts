import { Code, ConnectError } from '@connectrpc/connect';
import { NextRequest, NextResponse } from 'next/server';

function statusFor(code: Code) {
  if (code === Code.Unauthenticated) return 401;
  if (code === Code.PermissionDenied) return 403;
  if (code === Code.InvalidArgument) return 400;
  return 502;
}

export function grpcResponse(result: Promise<Record<string, unknown>>) {
  return result.then(
    body => NextResponse.json(body),
    (error: unknown) => {
      const connectError = ConnectError.from(error);
      return NextResponse.json(
        { message: connectError.rawMessage || 'request failed' },
        { status: statusFor(connectError.code) },
      );
    },
  );
}

export function authorization(request: NextRequest) {
  return request.headers.get('authorization');
}
