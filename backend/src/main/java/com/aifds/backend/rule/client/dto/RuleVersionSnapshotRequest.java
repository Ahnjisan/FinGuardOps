package com.aifds.backend.rule.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record RuleVersionSnapshotRequest(
        @JsonProperty(required = true) UUID fraudRuleId,
        @JsonProperty(required = true) String ruleCode,
        @JsonProperty(required = true) RuleLifecycleStatus lifecycleStatus,
        @JsonProperty(required = true) UUID ruleVersionId,
        @JsonProperty(required = true) int versionNumber,
        @JsonProperty(required = true) RuleVersionStatus status,
        @JsonProperty(required = true) String reasonCode,
        @JsonProperty(required = true) int weight,
        @JsonProperty(required = true) JsonNode conditionDefinition,
        @JsonProperty(required = true) Instant effectiveFrom,
        @JsonProperty(required = true) Instant effectiveTo
) {

    public RuleVersionSnapshotRequest {
        Objects.requireNonNull(fraudRuleId, "fraudRuleId must not be null");
        RuleAnalysisDtoContracts.requireUuidV4(fraudRuleId, "fraudRuleId");
        Objects.requireNonNull(ruleCode, "ruleCode must not be null");
        RuleAnalysisDtoContracts.requireText(ruleCode, "ruleCode");
        Objects.requireNonNull(lifecycleStatus, "lifecycleStatus must not be null");
        Objects.requireNonNull(ruleVersionId, "ruleVersionId must not be null");
        RuleAnalysisDtoContracts.requireUuidV4(ruleVersionId, "ruleVersionId");
        if (versionNumber < 1) {
            throw new IllegalArgumentException("versionNumber must be positive");
        }
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(reasonCode, "reasonCode must not be null");
        RuleAnalysisDtoContracts.requireText(reasonCode, "reasonCode");
        if (weight < 1 || weight > 100) {
            throw new IllegalArgumentException("weight must be between 1 and 100");
        }
        Objects.requireNonNull(
                conditionDefinition,
                "conditionDefinition must not be null"
        );
        if (!conditionDefinition.isObject()) {
            throw new IllegalArgumentException("conditionDefinition must be an object");
        }
        conditionDefinition = conditionDefinition.deepCopy();
        Objects.requireNonNull(effectiveFrom, "effectiveFrom must not be null");
        RuleAnalysisDtoContracts.requireMicrosecondInstant(
                effectiveFrom,
                "effectiveFrom"
        );
        if (effectiveTo != null) {
            RuleAnalysisDtoContracts.requireMicrosecondInstant(
                    effectiveTo,
                    "effectiveTo"
            );
            if (!effectiveTo.isAfter(effectiveFrom)) {
                throw new IllegalArgumentException(
                        "effectiveTo must be after effectiveFrom"
                );
            }
        }
    }

    @Override
    public JsonNode conditionDefinition() {
        return conditionDefinition.deepCopy();
    }
}
