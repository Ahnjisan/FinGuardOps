package com.aifds.backend.rule.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record RuleAnalysisResultResponse(
        @JsonProperty(required = true) Instant evaluationCutoffAt,
        @JsonProperty(required = true) String ruleSetVersion,
        @JsonProperty(required = true) RuleScoringResultResponse scoringResult,
        @JsonProperty(required = true) List<RuleEvidenceResponse> evidence
) {

    public RuleAnalysisResultResponse {
        Objects.requireNonNull(
                evaluationCutoffAt,
                "evaluationCutoffAt must not be null"
        );
        Objects.requireNonNull(ruleSetVersion, "ruleSetVersion must not be null");
        Objects.requireNonNull(scoringResult, "scoringResult must not be null");
        Objects.requireNonNull(evidence, "evidence must not be null");
        evidence.forEach(value -> Objects.requireNonNull(
                value,
                "evidence must not contain null"
        ));
        evidence = List.copyOf(evidence);
    }
}
