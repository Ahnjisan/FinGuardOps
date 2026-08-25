package com.aifds.backend.rule.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record RuleAnalysisRequestV2(
        @JsonProperty(required = true) Instant evaluationCutoffAt,
        @JsonProperty(required = true) RuleTransactionSnapshotRequest transaction,
        @JsonProperty(required = true) List<RuleBehaviorEventSnapshotRequest> behaviorEvents,
        @JsonProperty(required = true) List<RuleVersionSnapshotRequest> ruleVersions,
        @JsonProperty(required = true) ExternalRiskSnapshotRequest externalRisk
) {

    public RuleAnalysisRequestV2 {
        RuleAnalysisRequest validated = new RuleAnalysisRequest(
                evaluationCutoffAt,
                transaction,
                behaviorEvents,
                ruleVersions
        );
        evaluationCutoffAt = validated.evaluationCutoffAt();
        transaction = validated.transaction();
        behaviorEvents = validated.behaviorEvents();
        ruleVersions = validated.ruleVersions();
        Objects.requireNonNull(externalRisk, "externalRisk must not be null");
    }
}
