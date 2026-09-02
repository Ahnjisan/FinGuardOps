package com.aifds.backend.security.web;

import com.aifds.backend.common.error.ApiErrorResponse;
import com.aifds.backend.common.trace.TraceIdFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

public final class SecurityErrorResponseWriter {

    private final ObjectMapper objectMapper;

    public SecurityErrorResponseWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void write(
            HttpServletRequest request,
            HttpServletResponse response,
            int status,
            String code,
            String message
    ) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(
                response.getOutputStream(),
                new ApiErrorResponse(
                        code,
                        message,
                        traceId(request),
                        List.of()
                )
        );
    }

    private String traceId(HttpServletRequest request) {
        Object value = request.getAttribute(
                TraceIdFilter.TRACE_ID_REQUEST_ATTRIBUTE
        );
        return value instanceof String traceId ? traceId : null;
    }
}
