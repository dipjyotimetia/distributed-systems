/** @jest-environment node */

import { NextRequest } from 'next/server';
import { withTraceparent, createTraceParent } from '../trace-proxy';

test('createTraceParent returns a valid W3C header', () => {
  expect(createTraceParent()).toMatch(/^00-[a-f0-9]{32}-[a-f0-9]{16}-01$/);
});

test('withTraceparent continues inbound context or mints a new one', () => {
  const inbound = createTraceParent();
  const headers: Record<string, string> = {};
  const proxy = withTraceparent(
    new NextRequest('http://localhost/api/v1/accounts', {
      headers: { traceparent: inbound },
    }),
    headers,
    'test.proxy',
  );
  expect(headers.traceparent).toMatch(/^00-[a-f0-9]{32}-[a-f0-9]{16}-01$/);
  // Without a real TracerProvider, inject falls back to the inbound header.
  expect(headers.traceparent).toBe(inbound);
  proxy.end(200);
});

test('withTraceparent mints a header when none is inbound', () => {
  const headers = new Headers();
  const proxy = withTraceparent(new NextRequest('http://localhost/api/v1/x'), headers);
  expect(headers.get('traceparent')).toMatch(/^00-[a-f0-9]{32}-[a-f0-9]{16}-01$/);
  proxy.end(200);
});
