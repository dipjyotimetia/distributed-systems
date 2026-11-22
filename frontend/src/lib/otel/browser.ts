'use client';

let started = false;

/**
 * Browser OpenTelemetry bootstrap. Exports via same-origin /api/otel proxy
 * to avoid collector CORS issues.
 */
export async function registerBrowserOtel() {
  if (started || typeof window === 'undefined') {
    return;
  }
  if (process.env.NEXT_PUBLIC_OTEL_SDK_DISABLED === 'true') {
    return;
  }
  started = true;

  const [
    { WebTracerProvider },
    { BatchSpanProcessor },
    { OTLPTraceExporter },
    { resourceFromAttributes },
    { ATTR_SERVICE_NAME, ATTR_SERVICE_NAMESPACE },
    { ZoneContextManager },
    { registerInstrumentations },
    { FetchInstrumentation },
    { W3CTraceContextPropagator },
    api,
  ] = await Promise.all([
    import('@opentelemetry/sdk-trace-web'),
    import('@opentelemetry/sdk-trace-base'),
    import('@opentelemetry/exporter-trace-otlp-http'),
    import('@opentelemetry/resources'),
    import('@opentelemetry/semantic-conventions'),
    import('@opentelemetry/context-zone'),
    import('@opentelemetry/instrumentation'),
    import('@opentelemetry/instrumentation-fetch'),
    import('@opentelemetry/core'),
    import('@opentelemetry/api'),
  ]);

  api.propagation.setGlobalPropagator(new W3CTraceContextPropagator());

  const exporter = new OTLPTraceExporter({
    url: '/api/otel',
  });
  const provider = new WebTracerProvider({
    resource: resourceFromAttributes({
      [ATTR_SERVICE_NAME]: 'frontend-browser',
      [ATTR_SERVICE_NAMESPACE]: 'banking-app',
    }),
    spanProcessors: [new BatchSpanProcessor(exporter)],
  });
  provider.register({
    contextManager: new ZoneContextManager(),
  });

  registerInstrumentations({
    instrumentations: [
      new FetchInstrumentation({
        propagateTraceHeaderCorsUrls: [/.*/],
        clearTimingResources: true,
      }),
    ],
  });
}
