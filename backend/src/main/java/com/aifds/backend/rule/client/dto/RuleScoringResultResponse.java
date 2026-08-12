package com.aifds.backend.rule.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Objects;

public record RuleScoringResultResponse(
        @JsonProperty(required = true) String scoringPolicyVersion,
        @JsonProperty(required = true) int riskScore,
        @JsonProperty(required = true) RuleRiskLevel riskLevel,
        @JsonProperty(required = true) List<RuleContributionResponse> ruleContributions,
        @JsonProperty(required = true) List<RuleScoreGroupSummaryResponse> groupSummaries
) {

    public RuleScoringResultResponse {
        Objects.requireNonNull(
                scoringPolicyVersion,
                "scoringPolicyVersion must not be null"
        );
        Objects.requireNonNull(riskLevel, "riskLevel must not be null");
        ruleContributions = immutableNonNullElements(
                ruleContributions,
                "ruleContributions"
        );
        groupSummaries = immutableNonNullElements(groupSummaries, "groupSummaries");
    }

    private static <T> List<T> immutableNonNullElements(
            List<T> values,
            String field
    ) {
        Objects.requireNonNull(values, field + " must not be null");
        values.forEach(value -> Objects.requireNonNull(
                value,
                field + " must not contain null"
        ));
        return List.copyOf(values);
    }
}
