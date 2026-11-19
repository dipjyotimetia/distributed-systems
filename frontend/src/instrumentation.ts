/*
 * OpenTelemetry instrumentation for Next.js frontend
 * This file is automatically loaded by Next.js when the experimental.instrumentationHook is enabled
 * Note: This only runs on the server side during SSR/API routes, not in the browser
 */

// Type declaration for EdgeRuntime
declare global {
  const EdgeRuntime: string | undefined;
}

// Conditional logging based on OTEL_LOG_LEVEL environment variable
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

// Register the SDK - only runs on the server side
export async function register() {
  // Only initialize on server side to avoid bundling issues with browser
  if (process.env.OTEL_SDK_DISABLED === 'true') {
    return;
  }

  if (typeof window !== 'undefined') {
    return;
  }
  
  // Check if we're in Edge Runtime (middleware) - skip instrumentation
  if (typeof EdgeRuntime !== 'undefined') {
    log('🔧 EdgeRuntime detected, but continuing with Node.js instrumentation...');
    // Don't return - continue with instrumentation even in Edge Runtime
  }
  
  // Check if we're in a Node.js environment
  if (typeof process === 'undefined' || !process.versions?.node) {
    log('🔍 Skipping OpenTelemetry instrumentation - not in Node.js environment');
    return;
  }
  
  log('🔍 Registering OpenTelemetry instrumentation for frontend server...');
  log('🔧 Environment variables:');
  log('  - OTEL_SERVICE_NAME:', process.env.OTEL_SERVICE_NAME);
  log('  - OTEL_EXPORTER_OTLP_ENDPOINT:', process.env.OTEL_EXPORTER_OTLP_ENDPOINT);
  log('  - OTEL_RESOURCE_ATTRIBUTES:', process.env.OTEL_RESOURCE_ATTRIBUTES);
  log('  - NODE_ENV:', process.env.NODE_ENV);
  
  // Always log the basic startup message
  console.log('🚀 Starting OpenTelemetry instrumentation for frontend service');
  
  try {
    // Skip OTEL instrumentation during build to avoid type compatibility issues
    if (process.env.NODE_ENV === 'production' && !process.env.OTEL_SERVICE_NAME) {
      log('🔍 Skipping OpenTelemetry instrumentation during build');
      return;
    }

    // Use a minimal approach that avoids NodeSDK to prevent bundling issues
    const { NodeTracerProvider } = await import('@opentelemetry/sdk-trace-node');
    const { ConsoleSpanExporter, SimpleSpanProcessor } = await import('@opentelemetry/sdk-trace-base');
    const { OTLPTraceExporter } = await import('@opentelemetry/exporter-trace-otlp-http');
    const { resourceFromAttributes } = await import('@opentelemetry/resources');
    const { ATTR_SERVICE_NAME, ATTR_SERVICE_VERSION, ATTR_SERVICE_NAMESPACE } = await import('@opentelemetry/semantic-conventions');
    const { trace, context, propagation } = await import('@opentelemetry/api');
    
    log('🔧 OpenTelemetry packages loaded successfully');
    
    // Note: W3C Trace Context propagator is set by default in OpenTelemetry
    // No need to explicitly set it as it's the default propagator
    
    const otlpExporter = new OTLPTraceExporter({
      url: process.env.OTEL_EXPORTER_OTLP_ENDPOINT || 'http://localhost:4318/v1/traces',
    });
    const spanProcessors = [new SimpleSpanProcessor(otlpExporter)];
    if (isDebugMode) {
      spanProcessors.push(new SimpleSpanProcessor(new ConsoleSpanExporter()));
    }

    const tracerProvider = new NodeTracerProvider({
      resource: resourceFromAttributes({
        [ATTR_SERVICE_NAME]: process.env.OTEL_SERVICE_NAME || 'frontend',
        [ATTR_SERVICE_VERSION]: '1.0.0',
        [ATTR_SERVICE_NAMESPACE]: resourceAttribute('service.namespace', 'banking-app'),
        'deployment.environment': resourceAttribute('deployment.environment', 'decoy'),
      }),
      spanProcessors,
    });
    tracerProvider.register();

    log('✅ OpenTelemetry instrumentation registered successfully for frontend server');
    log('🔧 Service name:', process.env.OTEL_SERVICE_NAME || 'frontend');
    log('🔧 Service namespace:', resourceAttribute('service.namespace', 'banking-app'));

    // Gracefully shutdown the tracer provider on process exit
    process.on('SIGTERM', () => {
      log('🔄 Shutting down OpenTelemetry tracer provider...');
      tracerProvider.shutdown()
        .then(() => log('✅ OpenTelemetry tracer provider shutdown successfully'))
        .catch((error) => log('❌ Error shutting down OpenTelemetry tracer provider:', error))
        .finally(() => process.exit(0));
    });

    // Test the tracer by creating a simple span
    const testTracer = trace.getTracer('frontend-test');
    const testSpan = testTracer.startSpan('test-span');
    testSpan.setAttribute('test.attribute', 'test-value');
    testSpan.end();
    log('🔧 Test span created and ended');

    // Create a utility function to get current trace context for API calls
    const getCurrentTraceContext = () => {
      const currentContext = context.active();
      const carrier: Record<string, string> = {};
      propagation.inject(currentContext, carrier);
      return carrier['traceparent'] || null;
    };

    // Expose trace context utilities to process.env for API client access
    // This is a safer approach than using global in browser environments
    if (typeof process !== 'undefined') {
      // Extend process with our trace utilities
      interface ProcessWithOtelUtils extends NodeJS.Process {
        __OTEL_TRACE_UTILS__?: {
          getCurrentTraceContext: () => string | null;
          createSpan: (name: string, attributes?: Record<string, string | number | boolean>) => unknown;
        };
      }
      (process as ProcessWithOtelUtils).__OTEL_TRACE_UTILS__ = {
        getCurrentTraceContext,
        createSpan: (name: string, attributes?: Record<string, string | number | boolean>) => {
          const tracer = trace.getTracer('frontend-api');
          const span = tracer.startSpan(name);
          if (attributes) {
            Object.entries(attributes).forEach(([key, value]) => {
              span.setAttribute(key, value);
            });
          }
          return span;
        }
      };
    }
  } catch (error) {
    console.error('❌ Error registering OpenTelemetry instrumentation:', error);
  }
}
