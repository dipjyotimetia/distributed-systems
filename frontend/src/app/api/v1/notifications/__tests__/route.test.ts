/** @jest-environment node */

import { NextRequest } from 'next/server';
import { GET } from '../route';

const originalFetch = global.fetch;
const mockFetch = jest.fn();

beforeEach(() => {
  global.fetch = mockFetch;
  mockFetch.mockReset();
});
afterAll(() => { global.fetch = originalFetch; });

test('requires a session and forwards it to the customer inbox', async () => {
  expect((await GET(new NextRequest('http://localhost/api/v1/notifications'))).status).toBe(401);
  mockFetch.mockResolvedValueOnce(new Response('[]', { status: 200, headers: { 'content-type': 'application/json' } }));
  const result = await GET(new NextRequest('http://localhost/api/v1/notifications', {
    headers: { authorization: 'Bearer session' },
  }));
  expect(result.status).toBe(200);
  expect(await result.json()).toEqual([]);
  expect(mockFetch).toHaveBeenCalledWith('http://notification-service:8080/api/v1/notifications/me', expect.objectContaining({
    headers: { authorization: 'Bearer session' },
  }));
});

test('reports an unreachable notification service', async () => {
  mockFetch.mockRejectedValueOnce(new Error('offline'));
  const result = await GET(new NextRequest('http://localhost/api/v1/notifications', {
    headers: { authorization: 'Bearer session' },
  }));
  expect(result.status).toBe(502);
});
