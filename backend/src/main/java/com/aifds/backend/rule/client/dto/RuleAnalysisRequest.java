package com.aifds.backend.rule.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record RuleAnalysisRequest(
        @JsonProperty(required = true) Instant evaluationCutoffAt,
        @JsonProperty(required = true) RuleTransactionSnapshotRequest transaction,
        @JsonProperty(required = true) List<RuleBehaviorEventSnapshotRequest> behaviorEvents,
        @JsonProperty(required = true) List<RuleVersionSnapshotRequest> ruleVersions
) {

    public RuleAnalysisRequest {
        Objects.requireNonNull(
                evaluationCutoffAt,
                "evaluationCutoffAt must not be null"
        );
        RuleAnalysisDtoContracts.requireMicrosecondInstant(
                evaluationCutoffAt,
                "evaluationCutoffAt"
        );
        Objects.requireNonNull(transaction, "transaction must not be null");
        behaviorEvents = immutableNonNullElements(behaviorEvents, "behaviorEvents");
        ruleVersions = immutableNonNullElements(ruleVersions, "ruleVersions");
        if (!evaluationCutoffAt.equals(transaction.occurredAt())) {
            throw new IllegalArgumentException(
                    "evaluationCutoffAt must equal transaction.occurredAt"
            );
        }
        if (behaviorEvents.size() > 1_000) {
            throw new IllegalArgumentException(
                    "behaviorEvents must contain at most 1000 items"
            );
        }
        if (ruleVersions.isEmpty() || ruleVersions.size() > 32) {
            throw new IllegalArgumentException(
                    "ruleVersions must contain between 1 and 32 items"
            );
        }
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
