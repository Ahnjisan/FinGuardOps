package com.aifds.backend.rule.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

public record RuleScoreGroupSummaryResponse(
        @JsonProperty(required = true) RuleScoreGroupId groupId,
        @JsonProperty(required = true) int rawScore,
        @JsonProperty(required = true) int cap,
        @JsonProperty(required = true) int appliedScore,
        @JsonProperty(required = true) int reduction
) {

    public RuleScoreGroupSummaryResponse {
        Objects.requireNonNull(groupId, "groupId must not be null");
    }
}
