package com.aifds.backend.security.web;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;

public final class FinGuardOpsAccessDeniedHandler
        implements AccessDeniedHandler {

    static final String ACCESS_DENIED_MESSAGE =
            "요청한 작업을 수행할 권한이 없습니다.";

    private final SecurityErrorResponseWriter responseWriter;

    public FinGuardOpsAccessDeniedHandler(
            SecurityErrorResponseWriter responseWriter
    ) {
        this.responseWriter = responseWriter;
    }

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException accessDeniedException
    ) throws IOException, ServletException {
        response.setHeader(
                "WWW-Authenticate",
                "Bearer realm=\"finguardops-backend\", "
                        + "error=\"insufficient_scope\""
        );
        responseWriter.write(
                request,
                response,
                HttpServletResponse.SC_FORBIDDEN,
                "ACCESS_DENIED",
                ACCESS_DENIED_MESSAGE
        );
    }
}
