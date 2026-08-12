package com.aifds.backend.rule.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

public record RuleAnalysisFieldErrorResponse(
        @JsonProperty(required = true) String field,
        @JsonProperty(required = true) String code,
        @JsonProperty(required = true) String reason
) {

    public RuleAnalysisFieldErrorResponse {
        Objects.requireNonNull(field, "field must not be null");
        Objects.requireNonNull(code, "code must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
    }
}
