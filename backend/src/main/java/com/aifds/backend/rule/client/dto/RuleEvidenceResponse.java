package com.aifds.backend.rule.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record RuleEvidenceResponse(
        @JsonProperty(required = true) RuleId ruleId,
        @JsonProperty(required = true) UUID ruleVersionId,
        @JsonProperty(required = true) String ruleCode,
        @JsonProperty(required = true) String ruleVersion,
        @JsonProperty(required = true) String reasonCode,
        @JsonProperty(required = true) int executionOrder,
        @JsonProperty(required = true) int scoreContribution,
        @JsonProperty(required = true) JsonNode observationSummary,
        @JsonProperty(required = true) Instant evidenceOccurredAt
) {

    public RuleEvidenceResponse {
        Objects.requireNonNull(ruleId, "ruleId must not be null");
        Objects.requireNonNull(ruleVersionId, "ruleVersionId must not be null");
        Objects.requireNonNull(ruleCode, "ruleCode must not be null");
        Objects.requireNonNull(ruleVersion, "ruleVersion must not be null");
        Objects.requireNonNull(reasonCode, "reasonCode must not be null");
        Objects.requireNonNull(
                observationSummary,
                "observationSummary must not be null"
        );
        observationSummary = observationSummary.deepCopy();
        Objects.requireNonNull(
                evidenceOccurredAt,
                "evidenceOccurredAt must not be null"
        );
    }

    @Override
    public JsonNode observationSummary() {
        return observationSummary.deepCopy();
    }
}
