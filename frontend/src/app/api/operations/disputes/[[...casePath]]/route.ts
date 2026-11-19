import { disputeProxy } from '@/lib/api/disputes-proxy';

export const runtime = 'nodejs';
export const GET = disputeProxy(true);
export const POST = GET;
