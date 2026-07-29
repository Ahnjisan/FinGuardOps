package com.aifds.backend.common.trace;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Enumeration;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String TRACE_ID_HEADER = "X-Trace-Id";
    public static final String TRACE_ID_REQUEST_ATTRIBUTE =
            TraceIdFilter.class.getName() + ".traceId";
    public static final String TRACE_ID_MDC_KEY = "traceId";

    private static final int MIN_TRACE_ID_LENGTH = 8;
    private static final int MAX_TRACE_ID_LENGTH = 64;
    private static final Pattern EXTERNAL_TRACE_ID_PATTERN = Pattern.compile(
            "^[A-Za-z0-9][A-Za-z0-9._:-]{"
                    + (MIN_TRACE_ID_LENGTH - 1)
                    + ","
                    + (MAX_TRACE_ID_LENGTH - 1)
                    + "}$"
    );

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String traceId = resolveTraceId(request);

        request.setAttribute(TRACE_ID_REQUEST_ATTRIBUTE, traceId);
        MDC.put(TRACE_ID_MDC_KEY, traceId);
        response.setHeader(TRACE_ID_HEADER, traceId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(TRACE_ID_MDC_KEY);
        }
    }

    private String resolveTraceId(HttpServletRequest request) {
        Enumeration<String> headerValues =
                request.getHeaders(TRACE_ID_HEADER);
        if (headerValues == null || !headerValues.hasMoreElements()) {
            return generateTraceId();
        }

        String candidate = headerValues.nextElement();
        if (headerValues.hasMoreElements()
                || candidate == null
                || !EXTERNAL_TRACE_ID_PATTERN.matcher(candidate).matches()) {
            return generateTraceId();
        }
        return candidate;
    }

    private String generateTraceId() {
        return UUID.randomUUID().toString();
    }
}
