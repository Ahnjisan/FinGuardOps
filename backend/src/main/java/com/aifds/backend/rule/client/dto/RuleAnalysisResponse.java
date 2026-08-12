package com.aifds.backend.rule.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;
import java.util.UUID;

public record RuleAnalysisResponse(
        @JsonProperty(required = true) UUID transactionId,
        @JsonProperty(required = true) String traceId,
        @JsonProperty(required = true) RuleAnalysisResultResponse analysis
) {

    public RuleAnalysisResponse {
        Objects.requireNonNull(transactionId, "transactionId must not be null");
        Objects.requireNonNull(traceId, "traceId must not be null");
        Objects.requireNonNull(analysis, "analysis must not be null");
    }
}
