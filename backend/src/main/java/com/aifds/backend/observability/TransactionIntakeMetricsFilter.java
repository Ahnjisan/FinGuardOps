package com.aifds.backend.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.IOException;

@Component
@Order(Ordered.LOWEST_PRECEDENCE - 10)
public final class TransactionIntakeMetricsFilter extends OncePerRequestFilter {

    private static final String PUBLIC_INTAKE_PATH = "/api/v1/transactions";
    private static final String STATE_ATTRIBUTE =
            TransactionIntakeMetricsFilter.class.getName() + ".state";

    private final TransactionProcessingMetricsRecorder recorder;

    @Autowired
    public TransactionIntakeMetricsFilter(
            ObjectProvider<TransactionProcessingMetricsRecorder>
                    recorderProvider
    ) {
        this(safeRecorder(recorderProvider));
    }

    public TransactionIntakeMetricsFilter(
            TransactionProcessingMetricsRecorder recorder
    ) {
        this.recorder = recorder == null
                ? TransactionProcessingMetricsRecorder.noop()
                : recorder;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request == null
                || !"POST".equals(request.getMethod())
                || !PUBLIC_INTAKE_PATH.equals(requestPath(request));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        ObservationState state = new ObservationState();
        request.setAttribute(STATE_ATTRIBUTE, state);
        try {
            filterChain.doFilter(request, response);
        } finally {
            finishOnce(state);
        }
    }

    public static void markOutcome(
            HttpServletRequest request,
            TransactionProcessingMetricsRecorder.IntakeOutcome outcome
    ) {
        ObservationState state = state(request);
        if (state != null && outcome != null) {
            state.outcome = outcome;
        }
    }

    public static void markOutcomeIfAbsent(
            HttpServletRequest request,
            TransactionProcessingMetricsRecorder.IntakeOutcome outcome
    ) {
        ObservationState state = state(request);
        if (state != null && state.outcome == null && outcome != null) {
            state.outcome = outcome;
        }
    }

    public static void markCurrentOutcomeIfAbsent(
            TransactionProcessingMetricsRecorder.IntakeOutcome outcome
    ) {
        if (RequestContextHolder.getRequestAttributes()
                instanceof ServletRequestAttributes attributes) {
            markOutcomeIfAbsent(attributes.getRequest(), outcome);
        }
    }

    public static void markDuplicate(
            HttpServletRequest request,
            TransactionProcessingMetricsRecorder.DuplicateResult result
    ) {
        ObservationState state = state(request);
        if (state != null) {
            state.duplicateResult = result;
        }
    }

    public static void markIdempotencyConflict(HttpServletRequest request) {
        ObservationState state = state(request);
        if (state != null) {
            state.idempotencyConflict = true;
        }
    }

    private void finishOnce(ObservationState state) {
        if (state == null || state.finished) {
            return;
        }
        state.finished = true;
        TransactionProcessingMetricsRecorder.IntakeOutcome outcome =
                state.outcome == null
                        ? TransactionProcessingMetricsRecorder.IntakeOutcome
                        .INTERNAL_FAILURE
                        : state.outcome;
        try {
            recorder.recordIntakeOutcome(outcome);
        } catch (Throwable ignored) {
            // A recorder implementation must not affect the HTTP result.
        }
        if (state.duplicateResult != null) {
            try {
                recorder.recordDuplicateRequest(state.duplicateResult);
            } catch (Throwable ignored) {
                // A recorder implementation must not affect the HTTP result.
            }
        }
        if (state.idempotencyConflict) {
            try {
                recorder.recordIdempotencyConflict();
            } catch (Throwable ignored) {
                // A recorder implementation must not affect the HTTP result.
            }
        }
    }

    private static ObservationState state(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        Object value = request.getAttribute(STATE_ATTRIBUTE);
        return value instanceof ObservationState state ? state : null;
    }

    private String requestPath(HttpServletRequest request) {
        String requestUri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (requestUri == null) {
            return null;
        }
        if (contextPath != null
                && !contextPath.isEmpty()
                && requestUri.startsWith(contextPath)) {
            return requestUri.substring(contextPath.length());
        }
        return requestUri;
    }

    private static TransactionProcessingMetricsRecorder safeRecorder(
            ObjectProvider<TransactionProcessingMetricsRecorder>
                    recorderProvider
    ) {
        if (recorderProvider == null) {
            return TransactionProcessingMetricsRecorder.noop();
        }
        try {
            TransactionProcessingMetricsRecorder recorder =
                    recorderProvider.getIfAvailable();
            return recorder == null
                    ? TransactionProcessingMetricsRecorder.noop()
                    : recorder;
        } catch (Throwable ignored) {
            return TransactionProcessingMetricsRecorder.noop();
        }
    }

    private static final class ObservationState {
        private TransactionProcessingMetricsRecorder.IntakeOutcome outcome;
        private TransactionProcessingMetricsRecorder.DuplicateResult
                duplicateResult;
        private boolean idempotencyConflict;
        private boolean finished;
    }
}
