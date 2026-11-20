package com.banking.payments;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
public class PaymentTracing {
    private static final String TRACE_PARENT = "traceparent";
    private static final W3CTraceContextPropagator W3C = W3CTraceContextPropagator.getInstance();
    private static final TextMapGetter<String> READER = new TextMapGetter<>() {
        public Iterable<String> keys(String carrier) { return List.of(TRACE_PARENT); }
        public String get(String carrier, String key) { return TRACE_PARENT.equalsIgnoreCase(key) ? carrier : null; }
    };
    private final Tracer tracer;

    public PaymentTracing(OpenTelemetry telemetry) {
        this.tracer = telemetry.getTracer("banking.payments");
    }

    public static String currentParent() {
        var value = new java.util.concurrent.atomic.AtomicReference<String>();
        W3C.inject(Context.current(), value, (carrier, key, header) -> {
            if (TRACE_PARENT.equals(key)) carrier.set(header);
        });
        return value.get();
    }

    public Span start(String operation, UUID paymentId, String parent) {
        var builder = tracer.spanBuilder(operation).setAttribute("payment.id", paymentId.toString());
        if (parent != null) builder.setParent(W3C.extract(Context.root(), parent, READER));
        return builder.startSpan();
    }
}
