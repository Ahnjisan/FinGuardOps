package com.aifds.backend.common.error;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public record ApiErrorResponse(
        String code,
        String message,
        @JsonInclude(JsonInclude.Include.ALWAYS) String traceId,
        List<FieldErrorResponse> fieldErrors
) {

    private static final Comparator<FieldErrorResponse> FIELD_ERROR_ORDER =
            Comparator.comparing(FieldErrorResponse::field)
                    .thenComparing(FieldErrorResponse::code)
                    .thenComparing(FieldErrorResponse::reason);

    public ApiErrorResponse {
        Objects.requireNonNull(code, "code must not be null");
        Objects.requireNonNull(message, "message must not be null");

        if (fieldErrors == null) {
            fieldErrors = List.of();
        } else {
            fieldErrors.forEach(error ->
                    Objects.requireNonNull(
                            error,
                            "fieldErrors must not contain null"
                    )
            );
            fieldErrors = fieldErrors.stream()
                    .distinct()
                    .sorted(FIELD_ERROR_ORDER)
                    .toList();
        }
    }
}
