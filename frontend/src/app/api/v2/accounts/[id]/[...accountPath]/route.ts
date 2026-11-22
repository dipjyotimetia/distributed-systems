import { NextRequest, NextResponse } from 'next/server';
import { forwardFinancialRequest, uuidPath } from '@/lib/api/financial-proxy';

async function forward(
  request: NextRequest,
  context: { params: Promise<{ id: string; accountPath: string[] }> },
) {
  const {
    id,
    accountPath: [resource, job, download, ...rest],
  } = await context.params;
  const valid =
    request.method === 'POST'
      ? resource === 'statement-jobs' && !job
      : (resource === 'balances' && !job) ||
        (resource === 'statement-jobs' &&
          uuidPath.test(job ?? '') &&
          (!download || download === 'download'));
  if (!/^[1-9][0-9]{0,18}$/.test(id) || !valid || rest.length) {
    return NextResponse.json({ code: 'NOT_FOUND' }, { status: 404 });
  }
  const path = [resource, job, download].filter(Boolean).join('/');
  return forwardFinancialRequest(request, `/api/v2/accounts/${id}/${path}`);
}

export const GET = forward;
export const POST = forward;
