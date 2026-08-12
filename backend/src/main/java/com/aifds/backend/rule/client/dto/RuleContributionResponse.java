package com.aifds.backend.rule.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

public record RuleContributionResponse(
        @JsonProperty(required = true) RuleId ruleId,
        @JsonProperty(required = true) int executionOrder,
        @JsonProperty(required = true) boolean matched,
        @JsonProperty(required = true) int originalContribution
) {

    public RuleContributionResponse {
        Objects.requireNonNull(ruleId, "ruleId must not be null");
    }
}
