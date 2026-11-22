import { NextRequest, NextResponse } from 'next/server';
import {
  AccountsApi,
  Configuration,
  ResponseError,
} from '@/gen/openapi/accounts/src';
import { withTraceparent } from '@/lib/api/trace-proxy';

async function upstream(
  request: Promise<{ raw: Response }>,
): Promise<Response> {
  try {
    return (await request).raw;
  } catch (error) {
    if (error instanceof ResponseError) return error.response;
    throw error;
  }
}

export const runtime = 'nodejs';

const API_GATEWAY_URL = process.env.API_GATEWAY_URL || 'http://localhost:8080';

export async function GET(request: NextRequest) {
  const authHeader = request.headers.get('authorization');
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    ...(authHeader && { Authorization: authHeader }),
  };
  const proxy = withTraceparent(request, headers, 'proxy accounts list');
  try {
    const { searchParams } = new URL(request.url);

    const api = new AccountsApi(
      new Configuration({
        basePath: API_GATEWAY_URL,
        headers,
      }),
    );
    const page = searchParams.get('page');
    const response = await upstream(
      api.listAccountsRaw({
        ...(page != null && { page: Number(page) }),
        ...(searchParams.get('size') != null && {
          size: Number(searchParams.get('size')),
        }),
        ...(searchParams.get('sort') != null && {
          sort: searchParams.get('sort')!,
        }),
        ...(searchParams.get('type') != null && {
          type: searchParams.get('type') as 'CHECKING',
        }),
        ...(searchParams.get('status') != null && {
          status: searchParams.get('status') as 'ACTIVE',
        }),
      }),
    );

    proxy.end(response.status);
    const contentType = response.headers.get('content-type');
    return new NextResponse(response.body, {
      status: response.status,
      headers: contentType ? { 'Content-Type': contentType } : undefined,
    });
  } catch (error) {
    proxy.end(undefined, error);
    console.error('Accounts proxy error:', error);
    return NextResponse.json(
      { message: 'Internal server error' },
      { status: 500 },
    );
  }
}

export async function POST(request: NextRequest) {
  const authHeader = request.headers.get('authorization');
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    ...(authHeader && { Authorization: authHeader }),
  };
  const proxy = withTraceparent(request, headers, 'proxy accounts create');
  try {
    const body = await request.json();

    const api = new AccountsApi(
      new Configuration({
        basePath: API_GATEWAY_URL,
        headers,
      }),
    );
    const response = await upstream(
      api.createAccountRaw({ accountCreateRequest: body }),
    );

    proxy.end(response.status);
    const contentType = response.headers.get('content-type');
    return new NextResponse(response.body, {
      status: response.status,
      headers: contentType ? { 'Content-Type': contentType } : undefined,
    });
  } catch (error) {
    proxy.end(undefined, error);
    console.error('Create account proxy error:', error);
    return NextResponse.json(
      { message: 'Internal server error' },
      { status: 500 },
    );
  }
}
