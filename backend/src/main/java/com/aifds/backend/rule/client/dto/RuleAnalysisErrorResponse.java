package com.aifds.backend.rule.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Objects;

public record RuleAnalysisErrorResponse(
        @JsonProperty(required = true) String code,
        @JsonProperty(required = true) String message,
        @JsonProperty(required = true) String traceId,
        @JsonProperty(required = true) List<RuleAnalysisFieldErrorResponse> fieldErrors
) {

    public RuleAnalysisErrorResponse {
        Objects.requireNonNull(code, "code must not be null");
        Objects.requireNonNull(message, "message must not be null");
        Objects.requireNonNull(traceId, "traceId must not be null");
        Objects.requireNonNull(fieldErrors, "fieldErrors must not be null");
        fieldErrors.forEach(value -> Objects.requireNonNull(
                value,
                "fieldErrors must not contain null"
        ));
        fieldErrors = List.copyOf(fieldErrors);
    }
}
