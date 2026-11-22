const { context, propagation, trace, SpanStatusCode } = require('@opentelemetry/api');
const { NodeTracerProvider } = require('@opentelemetry/sdk-trace-node');
const { BatchSpanProcessor } = require('@opentelemetry/sdk-trace-base');
const { OTLPTraceExporter } = require('@opentelemetry/exporter-trace-otlp-http');
const { resourceFromAttributes } = require('@opentelemetry/resources');
const { ATTR_SERVICE_NAME, ATTR_SERVICE_NAMESPACE } = require('@opentelemetry/semantic-conventions');
const { W3CTraceContextPropagator } = require('@opentelemetry/core');
const config = require('../config');
const logger = require('./logger');

let provider;

function initializeTracing() {
  try {
    if (process.env.OTEL_SDK_DISABLED === 'true') {
      return null;
    }
    const endpoint = config.observability?.otel?.endpoint
      || process.env.OTEL_EXPORTER_OTLP_ENDPOINT
      || 'http://localhost:4318/v1/traces';
    const url = endpoint.includes('/v1/traces') ? endpoint : `${endpoint.replace(/\/$/, '')}/v1/traces`;
    propagation.setGlobalPropagator(new W3CTraceContextPropagator());
    provider = new NodeTracerProvider({
      resource: resourceFromAttributes({
        [ATTR_SERVICE_NAME]: config.observability?.otel?.serviceName || process.env.OTEL_SERVICE_NAME || 'simulation-client',
        [ATTR_SERVICE_NAMESPACE]: 'banking-app',
      }),
      spanProcessors: [new BatchSpanProcessor(new OTLPTraceExporter({ url }))],
    });
    provider.register();
    logger.info('OpenTelemetry tracing initialized', {
      serviceName: config.observability?.otel?.serviceName,
      endpoint: url,
    });
    return provider;
  } catch (error) {
    logger.error('Failed to initialize tracing', { error: error.message });
    return null;
  }
}

function injectTraceHeaders(headers = {}) {
  const carrier = { ...headers };
  propagation.inject(context.active(), carrier);
  return carrier;
}

function startSessionSpan(name, attributes = {}) {
  const tracer = trace.getTracer('simulation-client');
  const span = tracer.startSpan(name, { attributes });
  const ctx = trace.setSpan(context.active(), span);
  return {
    span,
    context: ctx,
    headers: (() => {
      const carrier = {};
      propagation.inject(ctx, carrier);
      return carrier;
    })(),
    end(error) {
      if (error) {
        span.recordException(error);
        span.setStatus({ code: SpanStatusCode.ERROR });
      } else {
        span.setStatus({ code: SpanStatusCode.OK });
      }
      span.end();
    },
  };
}

function shutdownTracing() {
  if (!provider) {
    return Promise.resolve();
  }
  return provider.shutdown().catch((error) => {
    logger.error('Tracing shutdown failed', { error: error.message });
  });
}

module.exports = {
  initializeTracing,
  shutdownTracing,
  injectTraceHeaders,
  startSessionSpan,
};
