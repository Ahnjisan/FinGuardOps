package com.aifds.backend.observability;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;

import java.util.UUID;

/** Local execution spans never reuse an ended HTTP request as their parent. */
public final class LocalTrace implements AutoCloseable {
    private static final Tracer TRACER = GlobalOpenTelemetry.getTracer("finguardops.backend");
    private final Span span;
    private final Scope scope;

    private LocalTrace(String operation, UUID executionId) {
        this.span = TRACER.spanBuilder(operation)
                .setParent(Context.root())
                .setAttribute("finguardops.execution_id", executionId.toString())
                .startSpan();
        this.scope = Context.root().with(span).makeCurrent();
    }

    public static LocalTrace execution(String operation, UUID executionId) {
        return new LocalTrace(operation, executionId);
    }

    public static String currentTraceId() {
        var context = Span.current().getSpanContext();
        return context.isValid() ? context.getTraceId() : "no-trace";
    }

    @Override
    public void close() {
        scope.close();
        span.end();
    }
}
