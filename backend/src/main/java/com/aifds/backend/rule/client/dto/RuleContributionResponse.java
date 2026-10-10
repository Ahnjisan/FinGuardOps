package com.aifds.backend.rule.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

public record RuleContributionResponse(
        @JsonProperty(required = true) RuleId ruleId,
        @JsonProperty(required = true) int executionOrder,
        @JsonProperty(required = true) boolean matched,
        @JsonProperty(required = true) int originalContribution,
        Integer appliedContribution
) {

    public RuleContributionResponse(RuleId ruleId, int executionOrder,
                                    boolean matched, int originalContribution) {
        this(ruleId, executionOrder, matched, originalContribution, null);
    }

    public RuleContributionResponse {
        Objects.requireNonNull(ruleId, "ruleId must not be null");
    }
}
