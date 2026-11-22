/*
 * Node.js OpenTelemetry registration. Kept out of instrumentation.ts so the
 * Next.js edge bundle does not compile Node APIs.
 */

const isDebugMode = process.env.OTEL_LOG_LEVEL === 'DEBUG';

function log(message: string, ...args: unknown[]) {
  if (isDebugMode) {
    console.log(message, ...args);
  }
}

function resourceAttribute(name: string, fallback: string): string {
  const attributes = process.env.OTEL_RESOURCE_ATTRIBUTES || '';
  for (const item of attributes.split(',')) {
    const [key, ...valueParts] = item.split('=');
    if (key?.trim() === name) {
      return valueParts.join('=').trim() || fallback;
    }
  }
  return fallback;
}

export async function registerNode() {
  if (process.env.OTEL_SDK_DISABLED === 'true') {
    return;
  }

  log('Registering OpenTelemetry instrumentation for frontend server...');
  log('Environment variables:');
  log('  - OTEL_SERVICE_NAME:', process.env.OTEL_SERVICE_NAME);
  log(
    '  - OTEL_EXPORTER_OTLP_ENDPOINT:',
    process.env.OTEL_EXPORTER_OTLP_ENDPOINT,
  );
  log('  - OTEL_RESOURCE_ATTRIBUTES:', process.env.OTEL_RESOURCE_ATTRIBUTES);
  log('  - NODE_ENV:', process.env.NODE_ENV);

  console.log('Starting OpenTelemetry instrumentation for frontend service');

  try {
    if (
      process.env.NODE_ENV === 'production' &&
      !process.env.OTEL_SERVICE_NAME
    ) {
      log('Skipping OpenTelemetry instrumentation during build');
      return;
    }

    const { NodeTracerProvider } =
      await import('@opentelemetry/sdk-trace-node');
    const { ConsoleSpanExporter, BatchSpanProcessor, SimpleSpanProcessor } =
      await import('@opentelemetry/sdk-trace-base');
    const { OTLPTraceExporter } =
      await import('@opentelemetry/exporter-trace-otlp-http');
    const { resourceFromAttributes } = await import('@opentelemetry/resources');
    const { ATTR_SERVICE_NAME, ATTR_SERVICE_VERSION, ATTR_SERVICE_NAMESPACE } =
      await import('@opentelemetry/semantic-conventions');
    const { trace, context, propagation } = await import('@opentelemetry/api');
    const { W3CTraceContextPropagator } = await import('@opentelemetry/core');

    log('OpenTelemetry packages loaded successfully');

    propagation.setGlobalPropagator(new W3CTraceContextPropagator());

    const otlpExporter = new OTLPTraceExporter({
      url:
        process.env.OTEL_EXPORTER_OTLP_ENDPOINT ||
        'http://localhost:4318/v1/traces',
    });
    const spanProcessors = [new BatchSpanProcessor(otlpExporter)];
    if (isDebugMode) {
      spanProcessors.push(new SimpleSpanProcessor(new ConsoleSpanExporter()));
    }

    const tracerProvider = new NodeTracerProvider({
      resource: resourceFromAttributes({
        [ATTR_SERVICE_NAME]: process.env.OTEL_SERVICE_NAME || 'frontend',
        [ATTR_SERVICE_VERSION]: '1.0.0',
        [ATTR_SERVICE_NAMESPACE]: resourceAttribute(
          'service.namespace',
          'banking-app',
        ),
        'deployment.environment': resourceAttribute(
          'deployment.environment',
          'decoy',
        ),
      }),
      spanProcessors,
    });
    tracerProvider.register();

    log(
      'OpenTelemetry instrumentation registered successfully for frontend server',
    );
    log('Service name:', process.env.OTEL_SERVICE_NAME || 'frontend');
    log(
      'Service namespace:',
      resourceAttribute('service.namespace', 'banking-app'),
    );

    process.on('SIGTERM', () => {
      log('Shutting down OpenTelemetry tracer provider...');
      tracerProvider
        .shutdown()
        .then(() => log('OpenTelemetry tracer provider shutdown successfully'))
        .catch(error =>
          log('Error shutting down OpenTelemetry tracer provider:', error),
        )
        .finally(() => process.exit(0));
    });

    const getCurrentTraceContext = () => {
      const currentContext = context.active();
      const carrier: Record<string, string> = {};
      propagation.inject(currentContext, carrier);
      return carrier['traceparent'] || null;
    };

    interface ProcessWithOtelUtils extends NodeJS.Process {
      __OTEL_TRACE_UTILS__?: {
        getCurrentTraceContext: () => string | null;
        createSpan: (
          name: string,
          attributes?: Record<string, string | number | boolean>,
        ) => unknown;
      };
    }
    (process as ProcessWithOtelUtils).__OTEL_TRACE_UTILS__ = {
      getCurrentTraceContext,
      createSpan: (
        name: string,
        attributes?: Record<string, string | number | boolean>,
      ) => {
        const tracer = trace.getTracer('frontend-api');
        const span = tracer.startSpan(name);
        if (attributes) {
          Object.entries(attributes).forEach(([key, value]) => {
            span.setAttribute(key, value);
          });
        }
        return span;
      },
    };
  } catch (error) {
    console.error('Error registering OpenTelemetry instrumentation:', error);
  }
}
