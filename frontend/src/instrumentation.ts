/*
 * OpenTelemetry instrumentation for Next.js frontend.
 * Next.js loads this file for the Node.js server and the edge runtime.
 * Node-only registration stays in instrumentation-node.ts.
 */

export async function register() {
  if (process.env.NEXT_RUNTIME === 'nodejs') {
    const { registerNode } = await import('./instrumentation-node');
    await registerNode();
  }
}
