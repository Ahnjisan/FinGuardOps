package com.aifds.backend.common.error;

import com.aifds.backend.common.trace.TraceIdFilter;
import com.aifds.backend.idempotency.exception.IdempotencyCompletionTransactionNotFoundException;
import com.aifds.backend.idempotency.exception.IdempotencyRecordNotFoundException;
import com.aifds.backend.idempotency.exception.IdempotencyStateTransitionNotAllowedException;
import com.aifds.backend.transaction.validation.IdempotencyKeyValidator;
import com.aifds.backend.transaction.validation.TransactionRequestValidator;
import com.aifds.backend.transaction.validation.TransactionValidationException;
import com.aifds.backend.transaction.validation.TransactionValidationType;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.converter.HttpMessageNotReadableException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@RestControllerAdvice
public class GlobalExceptionHandler {

    static final String VALIDATION_ERROR = "VALIDATION_ERROR";
    static final String STATE_TRANSITION_NOT_ALLOWED =
            "STATE_TRANSITION_NOT_ALLOWED";
    static final String INTERNAL_ERROR = "INTERNAL_ERROR";

    static final String VALIDATION_MESSAGE = "요청 필드를 확인해 주세요.";
    static final String STATE_TRANSITION_MESSAGE =
            "현재 상태에서는 요청한 처리를 수행할 수 없습니다.";
    static final String INTERNAL_ERROR_MESSAGE =
            "요청을 처리하는 중 오류가 발생했습니다.";

    private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    private static final String IDEMPOTENCY_KEY_REQUIRED_REASON =
            "Idempotency-Key is required";

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiErrorResponse> handleHttpMessageNotReadable(
            HttpMessageNotReadableException exception,
            HttpServletRequest request
    ) {
        Optional<TransactionValidationException> validationException =
                findCause(exception, TransactionValidationException.class);

        if (validationException.isPresent()) {
            return validationResponse(
                    HttpStatus.BAD_REQUEST,
                    List.of(toFieldError(validationException.get())),
                    request
            );
        }
        return validationResponse(HttpStatus.BAD_REQUEST, List.of(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiErrorResponse> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception,
            HttpServletRequest request
    ) {
        List<FieldErrorResponse> fieldErrors = new ArrayList<>();
        for (FieldError fieldError : exception.getBindingResult().getFieldErrors()) {
            if (fieldError != null
                    && "NotNull".equals(fieldError.getCode())
                    && fieldError.getField() != null) {
                String field = fieldError.getField();
                fieldErrors.add(new FieldErrorResponse(
                        field,
                        TransactionRequestValidator.REQUIRED_FIELD,
                        field + " is required"
                ));
            }
        }
        return validationResponse(
                HttpStatus.BAD_REQUEST,
                fieldErrors,
                request
        );
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<ApiErrorResponse> handleMissingRequestHeader(
            MissingRequestHeaderException exception,
            HttpServletRequest request
    ) {
        if (IDEMPOTENCY_KEY_HEADER.equalsIgnoreCase(
                exception.getHeaderName()
        )) {
            return validationResponse(
                    HttpStatus.BAD_REQUEST,
                    List.of(new FieldErrorResponse(
                            IDEMPOTENCY_KEY_HEADER,
                            IdempotencyKeyValidator.IDEMPOTENCY_KEY_REQUIRED,
                            IDEMPOTENCY_KEY_REQUIRED_REASON
                    )),
                    request
            );
        }
        return validationResponse(HttpStatus.BAD_REQUEST, List.of(), request);
    }

    @ExceptionHandler(TransactionValidationException.class)
    ResponseEntity<ApiErrorResponse> handleTransactionValidation(
            TransactionValidationException exception,
            HttpServletRequest request
    ) {
        HttpStatus status = exception.getType() == TransactionValidationType.DOMAIN
                ? HttpStatus.UNPROCESSABLE_ENTITY
                : HttpStatus.BAD_REQUEST;
        return validationResponse(
                status,
                List.of(toFieldError(exception)),
                request
        );
    }

    @ExceptionHandler(IdempotencyStateTransitionNotAllowedException.class)
    ResponseEntity<ApiErrorResponse> handleStateTransitionNotAllowed(
            IdempotencyStateTransitionNotAllowedException exception,
            HttpServletRequest request
    ) {
        return response(
                HttpStatus.CONFLICT,
                STATE_TRANSITION_NOT_ALLOWED,
                STATE_TRANSITION_MESSAGE,
                List.of(),
                request
        );
    }

    @ExceptionHandler({
            IdempotencyRecordNotFoundException.class,
            IdempotencyCompletionTransactionNotFoundException.class
    })
    ResponseEntity<ApiErrorResponse> handleInternalIdempotencyException(
            RuntimeException exception,
            HttpServletRequest request
    ) {
        return internalErrorResponse(request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiErrorResponse> handleUnexpectedException(
            Exception exception,
            HttpServletRequest request
    ) {
        return internalErrorResponse(request);
    }

    private ResponseEntity<ApiErrorResponse> validationResponse(
            HttpStatus status,
            List<FieldErrorResponse> fieldErrors,
            HttpServletRequest request
    ) {
        return response(
                status,
                VALIDATION_ERROR,
                VALIDATION_MESSAGE,
                fieldErrors,
                request
        );
    }

    private ResponseEntity<ApiErrorResponse> internalErrorResponse(
            HttpServletRequest request
    ) {
        return response(
                HttpStatus.INTERNAL_SERVER_ERROR,
                INTERNAL_ERROR,
                INTERNAL_ERROR_MESSAGE,
                List.of(),
                request
        );
    }

    private ResponseEntity<ApiErrorResponse> response(
            HttpStatus status,
            String code,
            String message,
            List<FieldErrorResponse> fieldErrors,
            HttpServletRequest request
    ) {
        ApiErrorResponse response = new ApiErrorResponse(
                code,
                message,
                currentTraceId(request),
                fieldErrors
        );
        return ResponseEntity.status(status).body(response);
    }

    private String currentTraceId(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        Object traceId = request.getAttribute(
                TraceIdFilter.TRACE_ID_REQUEST_ATTRIBUTE
        );
        return traceId instanceof String value ? value : null;
    }

    private FieldErrorResponse toFieldError(
            TransactionValidationException exception
    ) {
        return new FieldErrorResponse(
                exception.getField(),
                exception.getCode(),
                exception.getReason()
        );
    }

    private <T extends Throwable> Optional<T> findCause(
            Throwable throwable,
            Class<T> causeType
    ) {
        Set<Throwable> visited = Collections.newSetFromMap(
                new IdentityHashMap<>()
        );
        Throwable current = throwable;
        while (current != null && visited.add(current)) {
            if (causeType.isInstance(current)) {
                return Optional.of(causeType.cast(current));
            }
            current = current.getCause();
        }
        return Optional.empty();
    }
}
