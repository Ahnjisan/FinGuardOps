package com.aifds.backend.security.principal;

import com.aifds.backend.common.error.ApiErrorResponse;
import com.aifds.backend.common.trace.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;
import java.util.UUID;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public final class CurrentAuditActorProvider {

    private static final String ACCESS_DENIED_MESSAGE =
            "An authenticated USER principal is required";

    public UUID currentUserSubject() {
        Authentication authentication = SecurityContextHolder.getContext()
                .getAuthentication();
        if (!(authentication instanceof FinGuardOpsAuthenticationToken token)
                || !token.isAuthenticated()) {
            throw accessDenied();
        }

        FinGuardOpsPrincipal principal = token.getPrincipal();
        if (principal.type() != FinGuardOpsPrincipal.Type.USER) {
            throw accessDenied();
        }
        UUID subject = principal.subject();
        if (subject.version() != 4
                || subject.variant() != 2
                || !subject.toString().equals(subject.toString().toLowerCase())) {
            throw accessDenied();
        }
        return subject;
    }

    private AccessDeniedException accessDenied() {
        return new AccessDeniedException(ACCESS_DENIED_MESSAGE);
    }

    private static final String PUBLIC_ACCESS_DENIED_MESSAGE =
            "요청한 작업을 수행할 권한이 없습니다.";

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiErrorResponse> handle(
            HttpServletRequest request
    ) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(
                HttpHeaders.WWW_AUTHENTICATE,
                "Bearer realm=\"finguardops-backend\", "
                        + "error=\"insufficient_scope\""
        );
        Object traceValue = request.getAttribute(
                TraceIdFilter.TRACE_ID_REQUEST_ATTRIBUTE
        );
        String traceId = traceValue instanceof String value ? value : null;
        return new ResponseEntity<>(
                new ApiErrorResponse(
                        "ACCESS_DENIED",
                        PUBLIC_ACCESS_DENIED_MESSAGE,
                        traceId,
                        List.of()
                ),
                headers,
                HttpStatus.FORBIDDEN
        );
    }
}
