package com.aifds.backend.detection.service;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record RuleEvidenceDraft(
        UUID ruleVersionId,
        String displayDescription,
        JsonNode observationSummary,
        Instant evidenceOccurredAt,
        int sortOrder
) {

    public RuleEvidenceDraft {
        Objects.requireNonNull(
                ruleVersionId,
                "ruleVersionId must not be null"
        );
        Objects.requireNonNull(
                observationSummary,
                "observationSummary must not be null"
        );
        observationSummary = observationSummary.deepCopy();
    }

    @Override
    public JsonNode observationSummary() {
        return observationSummary.deepCopy();
    }
}
