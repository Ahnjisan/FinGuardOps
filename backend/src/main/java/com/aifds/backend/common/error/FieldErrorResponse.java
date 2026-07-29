package com.aifds.backend.common.error;

import java.util.Objects;

public record FieldErrorResponse(
        String field,
        String code,
        String reason
) {

    public FieldErrorResponse {
        Objects.requireNonNull(field, "field must not be null");
        Objects.requireNonNull(code, "code must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
    }
}
