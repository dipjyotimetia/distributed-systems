import {
  context,
  propagation,
  trace,
  SpanStatusCode,
} from '@opentelemetry/api';

type HeaderCarrier = Headers | Record<string, string>;

function headerEntries(request: Request): Record<string, string> {
  return Object.fromEntries(request.headers.entries());
}

function setHeader(carrier: HeaderCarrier, key: string, value: string) {
  if (carrier instanceof Headers) {
    carrier.set(key, value);
  } else {
    carrier[key] = value;
  }
}

function getHeader(carrier: HeaderCarrier, key: string): string | null {
  if (carrier instanceof Headers) {
    return carrier.get(key);
  }
  const found = Object.entries(carrier).find(
    ([k]) => k.toLowerCase() === key.toLowerCase(),
  );
  return found?.[1] ?? null;
}

function randomHex(bytes: number): string {
  const arr = new Uint8Array(bytes);
  crypto.getRandomValues(arr);
  return Array.from(arr, (b) => b.toString(16).padStart(2, '0')).join('');
}

/** Valid W3C traceparent when the SDK has no recording span context (e.g. tests). */
export function createTraceParent(): string {
  return `00-${randomHex(16)}-${randomHex(8)}-01`;
}

/**
 * Start (or continue) a proxy span from inbound W3C context and inject
 * traceparent/tracestate onto the upstream header carrier.
 */
export function withTraceparent(
  request: Request,
  headers: HeaderCarrier,
  spanName = 'frontend.proxy',
) {
  const parent = propagation.extract(context.active(), headerEntries(request));
  const span = trace
    .getTracer('frontend')
    .startSpan(spanName, undefined, parent);
  const spanCtx = trace.setSpan(parent, span);
  const injected: Record<string, string> = {};
  propagation.inject(spanCtx, injected);
  for (const [key, value] of Object.entries(injected)) {
    setHeader(headers, key, value);
  }
  // No-op tracers (tests / SDK not registered) may not inject; keep continuity.
  if (!getHeader(headers, 'traceparent')) {
    const inbound = request.headers.get('traceparent');
    setHeader(headers, 'traceparent', inbound || createTraceParent());
  }

  return {
    span,
    context: spanCtx,
    end(status?: number, error?: unknown) {
      if (error) {
        span.recordException(
          error instanceof Error ? error : new Error(String(error)),
        );
        span.setStatus({ code: SpanStatusCode.ERROR });
      } else if (status !== undefined && status >= 500) {
        span.setStatus({
          code: SpanStatusCode.ERROR,
          message: `HTTP ${status}`,
        });
      } else {
        span.setStatus({ code: SpanStatusCode.OK });
      }
      span.end();
    },
  };
}

function toHeaderRecord(init?: HeadersInit): Record<string, string> {
  const headers: Record<string, string> = {};
  if (!init) return headers;
  new Headers(init).forEach((value, key) => {
    headers[key] = value;
  });
  return headers;
}

/** Fetch upstream while continuing/creating W3C trace context from the inbound request. */
export async function tracedFetch(
  request: Request,
  url: string,
  init: RequestInit = {},
  spanName = 'frontend.proxy',
): Promise<Response> {
  const headers = toHeaderRecord(init.headers);
  const proxy = withTraceparent(request, headers, spanName);
  try {
    const response = await fetch(url, { ...init, headers });
    proxy.end(response.status);
    return response;
  } catch (error) {
    proxy.end(undefined, error);
    throw error;
  }
}
