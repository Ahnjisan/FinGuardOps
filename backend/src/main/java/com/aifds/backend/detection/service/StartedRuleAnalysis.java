package com.aifds.backend.detection.service;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record StartedRuleAnalysis(
        UUID transactionId,
        UUID detectionResultId,
        int detectionResultVersion,
        String ruleSetVersion,
        String scoringPolicyVersion,
        String featureVersion,
        String modelVersion,
        Instant evaluationCutoffAt,
        String analysisTraceId
) {

    public StartedRuleAnalysis {
        Objects.requireNonNull(transactionId, "transactionId must not be null");
        Objects.requireNonNull(
                detectionResultId,
                "detectionResultId must not be null"
        );
        if (detectionResultVersion < 1) {
            throw new IllegalArgumentException(
                    "detectionResultVersion must be positive"
            );
        }
        ruleSetVersion = requireText(ruleSetVersion, "ruleSetVersion");
        scoringPolicyVersion = requireText(
                scoringPolicyVersion,
                "scoringPolicyVersion"
        );
        featureVersion = requireText(featureVersion, "featureVersion");
        if (modelVersion != null) {
            modelVersion = requireText(modelVersion, "modelVersion");
        }
        Objects.requireNonNull(
                evaluationCutoffAt,
                "evaluationCutoffAt must not be null"
        );
        analysisTraceId = requireText(analysisTraceId, "analysisTraceId");
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    fieldName + " must not be blank"
            );
        }
        return value;
    }
}
