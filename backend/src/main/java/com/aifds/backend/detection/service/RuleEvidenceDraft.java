package com.aifds.backend.detection.service;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Objects;

public record RuleEvidenceDraft(
        String reasonCode,
        String displayDescription,
        int scoreContribution,
        String ruleCode,
        String ruleVersion,
        JsonNode observationSummary,
        Instant evidenceOccurredAt,
        int sortOrder
) {

    public RuleEvidenceDraft {
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
