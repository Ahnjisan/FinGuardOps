package com.aifds.backend.detection.service;

import com.aifds.backend.detection.entity.RiskLevel;

import java.util.Objects;
import java.util.UUID;

public record CompletedRuleAnalysis(
        UUID transactionId,
        UUID detectionResultId,
        int detectionResultVersion,
        int riskScore,
        RiskLevel riskLevel
) {

    public CompletedRuleAnalysis {
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
        if (riskScore < 0 || riskScore > 100) {
            throw new IllegalArgumentException(
                    "riskScore must be between 0 and 100"
            );
        }
        Objects.requireNonNull(riskLevel, "riskLevel must not be null");
    }
}
