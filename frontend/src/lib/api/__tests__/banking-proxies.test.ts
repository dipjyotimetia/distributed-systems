/** @jest-environment node */

import { NextRequest } from 'next/server';
import { GET as accounts } from '@/app/api/v1/accounts/route';
import { GET as statement, POST as createStatement } from '@/app/api/v1/accounts/[id]/statement-jobs/[[...jobPath]]/route';
import { sessionCookieOptions } from '@/lib/auth/session';
import { disputeProxy } from '../disputes-proxy';

const jobId = '5c11a3a3-f4ac-4d53-b582-abee1faee09b';
const accountParams = (id: string, jobPath?: string[]) => ({ params: Promise.resolve({ id, jobPath }) });
const disputeParams = (casePath?: string[]) => ({ params: Promise.resolve({ casePath }) });

afterEach(() => jest.restoreAllMocks());

test('accounts forwards the query and bearer token and preserves an upstream rejection', async () => {
  const upstream = jest.spyOn(globalThis, 'fetch').mockResolvedValue(Response.json({ message: 'Denied' }, { status: 403 }));
  const response = await accounts(new NextRequest('http://localhost/api/v1/accounts?page=2', {
    headers: { authorization: 'Bearer fixture' },
  }));
  expect(response.status).toBe(403);
  expect(await response.json()).toEqual({ message: 'Denied' });
  expect(upstream).toHaveBeenCalledWith(expect.stringContaining('/api/v1/accounts?page=2'), expect.objectContaining({
    headers: expect.objectContaining({ Authorization: 'Bearer fixture', traceparent: expect.stringMatching(/^00-[a-f0-9]{32}-[a-f0-9]{16}-01$/) }),
  }));
});

test('statement creation forwards the idempotency key and location', async () => {
  const upstream = jest.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(null, {
    status: 202, headers: { location: '/statement-jobs/' + jobId },
  }));
  const response = await createStatement(new NextRequest('http://localhost', {
    method: 'POST', headers: { 'idempotency-key': 'test-job' },
  }), accountParams('7'));
  expect(response.status).toBe(202);
  expect(response.headers.get('location')).toContain(jobId);
  expect(response.headers.get('cache-control')).toBe('no-store');
  expect(new Headers(upstream.mock.calls[0][1]?.headers).get('idempotency-key')).toBe('test-job');
});

test('statement download preserves content disposition and handles gateway outages', async () => {
  const upstream = jest.spyOn(globalThis, 'fetch')
    .mockResolvedValueOnce(new Response('fixture', { headers: { 'content-disposition': 'attachment; filename=statement.json' } }))
    .mockRejectedValueOnce(new Error('offline'));
  const request = new NextRequest('http://localhost');
  const download = await statement(request, accountParams('7', [jobId, 'download']));
  expect(download.headers.get('content-disposition')).toContain('statement.json');
  expect(await download.text()).toBe('fixture');
  expect((await statement(request, accountParams('7', [jobId]))).status).toBe(502);
  expect((await statement(request, accountParams('7', ['invalid']))).status).toBe(404);
  expect(upstream).toHaveBeenCalledTimes(2);
});

test('operations dispute updates forward JSON and reject unsupported paths and bodies', async () => {
  const upstream = jest.spyOn(globalThis, 'fetch').mockResolvedValue(Response.json({ status: 'RESOLVED' }));
  const send = disputeProxy(true);
  const request = (body: string, contentType: string) => new NextRequest('http://localhost/?page=1', {
    method: 'POST', body, headers: { 'content-type': contentType, authorization: 'Bearer fixture', 'idempotency-key': 'case-1' },
  });
  expect((await send(request('{}', 'application/json'), disputeParams(['7', 'resolve']))).status).toBe(200);
  expect(upstream.mock.calls[0][0]).toContain('/api/v1/operations/disputes/7/resolve?page=1');
  expect(new Headers(upstream.mock.calls[0][1]?.headers).get('idempotency-key')).toBe('case-1');
  expect((await send(request('{}', 'text/plain'), disputeParams(['7', 'resolve']))).status).toBe(415);
  expect((await send(request('x'.repeat(16385), 'application/json'), disputeParams(['7', 'resolve']))).status).toBe(413);
  expect((await send(request('{}', 'application/json'), disputeParams(['7', 'download']))).status).toBe(404);
  expect(upstream).toHaveBeenCalledTimes(1);
});

test('session cookies remain HttpOnly and use Secure when served behind HTTPS', () => {
  expect(sessionCookieOptions(new NextRequest('http://localhost'))).toEqual({
    httpOnly: true, sameSite: 'lax', secure: false, path: '/',
  });
  expect(sessionCookieOptions(new NextRequest('http://localhost', {
    headers: { 'x-forwarded-proto': 'https' },
  })).secure).toBe(true);
});
