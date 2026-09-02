package com.aifds.backend.security.web;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.io.IOException;

public final class FinGuardOpsAuthenticationEntryPoint
        implements AuthenticationEntryPoint {

    static final String UNAUTHORIZED_MESSAGE =
            "인증이 필요하거나 인증 정보가 유효하지 않습니다.";
    static final String DEPENDENCY_TIMEOUT_MESSAGE =
            "인증 서비스를 제한 시간 안에 사용할 수 없습니다.";
    static final String DEPENDENCY_UNAVAILABLE_MESSAGE =
            "인증 서비스를 일시적으로 사용할 수 없습니다.";
    static final String INTERNAL_ERROR_MESSAGE =
            "요청을 처리하는 중 오류가 발생했습니다.";

    private static final String BEARER_REALM =
            "Bearer realm=\"finguardops-backend\"";

    private final SecurityErrorResponseWriter responseWriter;
    private final RemoteJwkFailureClassifier failureClassifier;

    public FinGuardOpsAuthenticationEntryPoint(
            SecurityErrorResponseWriter responseWriter,
            RemoteJwkFailureClassifier failureClassifier
    ) {
        this.responseWriter = responseWriter;
        this.failureClassifier = failureClassifier;
    }

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authenticationException
    ) throws IOException, ServletException {
        RemoteJwkFailureClassifier.Classification classification =
                authenticationException instanceof InvalidBearerTokenException
                        ? failureClassifier.classify(authenticationException)
                        : RemoteJwkFailureClassifier.Classification.UNAUTHORIZED;

        switch (classification) {
            case UNAUTHORIZED -> unauthorized(
                    request,
                    response,
                    authenticationException
            );
            case DEPENDENCY_TIMEOUT -> responseWriter.write(
                    request,
                    response,
                    HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "DEPENDENCY_TIMEOUT",
                    DEPENDENCY_TIMEOUT_MESSAGE
            );
            case DEPENDENCY_UNAVAILABLE -> responseWriter.write(
                    request,
                    response,
                    HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "DEPENDENCY_UNAVAILABLE",
                    DEPENDENCY_UNAVAILABLE_MESSAGE
            );
            case INTERNAL_ERROR -> responseWriter.write(
                    request,
                    response,
                    HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "INTERNAL_ERROR",
                    INTERNAL_ERROR_MESSAGE
            );
        }
    }

    private void unauthorized(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authenticationException
    ) throws IOException {
        String challenge = authenticationException
                instanceof InvalidBearerTokenException
                ? BEARER_REALM + ", error=\"invalid_token\""
                : BEARER_REALM;
        response.setHeader("WWW-Authenticate", challenge);
        responseWriter.write(
                request,
                response,
                HttpServletResponse.SC_UNAUTHORIZED,
                "UNAUTHORIZED",
                UNAUTHORIZED_MESSAGE
        );
    }
}
