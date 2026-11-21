/** @jest-environment node */

import { NextRequest } from 'next/server';
import { GET, POST } from '../../api/v1/payments/[[...paymentPath]]/route';
import { GET as getQueue } from '../../api/v1/operations/payments/reconciliation/route';

const id = '597a4c24-019f-4ed6-884f-bf7cf8486f65';
const originalFetch = global.fetch;
const originalFlag = process.env.OUTBOUND_PAYMENTS_ENABLED;
const mockFetch = jest.fn();
const auth = { authorization: 'Bearer synthetic' };
const params = (paymentPath?: string[]) => ({ params: Promise.resolve({ paymentPath }) });

beforeEach(() => {
  process.env.OUTBOUND_PAYMENTS_ENABLED = 'true';
  global.fetch = mockFetch;
  mockFetch.mockReset();
});
afterAll(() => {
  global.fetch = originalFetch;
  if (originalFlag === undefined) delete process.env.OUTBOUND_PAYMENTS_ENABLED;
  else process.env.OUTBOUND_PAYMENTS_ENABLED = originalFlag;
});

function request(path: string, init?: ConstructorParameters<typeof NextRequest>[1]) {
  return new NextRequest('http://localhost' + path, init);
}

test('proxies the payment collection only when outbound payments are enabled', async () => {
  process.env.OUTBOUND_PAYMENTS_ENABLED = 'false';
  expect((await GET(request('/api/v1/payments'), params())).status).toBe(404);
  process.env.OUTBOUND_PAYMENTS_ENABLED = 'true';
  expect((await GET(request('/api/v1/payments'), params())).status).toBe(401);
  mockFetch.mockResolvedValueOnce(new Response('[]', { status: 200, headers: { 'content-type': 'application/json' } }));
  const result = await GET(request('/api/v1/payments', { headers: auth }), params());
  expect(result.status).toBe(200);
  expect(await result.json()).toEqual([]);
  expect(mockFetch).toHaveBeenCalledWith('http://localhost:8080/api/v1/payments', expect.objectContaining({ method: 'GET' }));
});

test('validates feature access, ownership credentials, and path format before forwarding', async () => {
  process.env.OUTBOUND_PAYMENTS_ENABLED = 'false';
  expect((await GET(request('/api/v1/payments/' + id), params([id]))).status).toBe(404);
  expect((await getQueue(request('/api/v1/operations/payments/reconciliation'))).status).toBe(404);
  process.env.OUTBOUND_PAYMENTS_ENABLED = 'true';
  expect((await GET(request('/api/v1/payments/' + id), params([id]))).status).toBe(401);
  expect((await GET(request('/api/v1/payments/no', { headers: auth }), params(['no']))).status).toBe(404);
  expect((await POST(request('/api/v1/payments/' + id, { method: 'POST', headers: auth }), params([id]))).status).toBe(404);
  expect(mockFetch).not.toHaveBeenCalled();
});

test('bounds payment bodies and validates content type and idempotency key', async () => {
  expect((await POST(request('/api/v1/payments', { method: 'POST', headers: auth }), params())).status).toBe(415);
  const headers = { ...auth, 'content-type': 'application/json' };
  expect((await POST(request('/api/v1/payments', { method: 'POST', headers, body: '{}' }), params())).status).toBe(400);
  const post = { method: 'POST', headers: { ...headers, 'idempotency-key': 'retry' } };
  expect((await POST(request('/api/v1/payments', post), params())).status).toBe(400);
  expect((await POST(request('/api/v1/payments', { ...post, body: 'x'.repeat(2049) }), params())).status).toBe(413);
});

test('forwards payment body, trace context, and response location', async () => {
  mockFetch.mockResolvedValueOnce(new Response('{}', { status: 202, headers: {
    'content-type': 'application/json', location: '/api/v1/payments/' + id,
  } }));
  const input = request('/api/v1/payments', {
    method: 'POST', body: '{}',
    headers: { ...auth, 'content-type': 'application/json; charset=utf-8', 'idempotency-key': 'retry', traceparent: 'trace' },
  });
  const result = await POST(input, params());
  expect(result.status).toBe(202);
  expect(result.headers.get('location')).toBe('/api/v1/payments/' + id);
  const [, options] = mockFetch.mock.calls[0];
  expect(options.headers.get('idempotency-key')).toBe('retry');
  expect(options.headers.get('traceparent')).toBe('trace');
  expect(new TextDecoder().decode(options.body)).toBe('{}');
  mockFetch.mockRejectedValueOnce(new Error('unavailable'));
  expect((await GET(request('/api/v1/payments/' + id, { headers: auth }), params([id]))).status).toBe(502);
});

test('validates the operator queue offset and preserves the gateway response', async () => {
  const path = '/api/v1/operations/payments/reconciliation';
  expect((await getQueue(request(path))).status).toBe(401);
  expect((await getQueue(request(path + '?offset=-1', { headers: auth }))).status).toBe(400);
  mockFetch.mockResolvedValueOnce(new Response('[]', { status: 200, headers: { 'content-type': 'application/json' } }));
  const result = await getQueue(request(path, { headers: auth }));
  expect(result.headers.get('cache-control')).toBe('no-store');
  expect(await result.json()).toEqual([]);
  mockFetch.mockRejectedValueOnce(new Error('unavailable'));
  expect((await getQueue(request(path, { headers: auth }))).status).toBe(502);
});
