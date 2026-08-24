package com.aifds.backend.transaction.service;

import com.aifds.backend.detection.entity.RiskLevel;
import com.aifds.backend.transaction.entity.RiskResponseOutcome;
import com.aifds.backend.transaction.entity.TransactionProcessingStatus;

import java.util.Objects;
import java.util.UUID;

public record RiskResponseFinalizationResult(
        UUID transactionId,
        UUID adoptedDetectionResultId,
        RiskLevel riskLevel,
        TransactionProcessingStatus processingStatus,
        RiskResponseOutcome riskResponseOutcome,
        UUID caseId,
        boolean caseCreated
) {

    public RiskResponseFinalizationResult {
        Objects.requireNonNull(
                transactionId,
                "transactionId must not be null"
        );
        Objects.requireNonNull(
                adoptedDetectionResultId,
                "adoptedDetectionResultId must not be null"
        );
        Objects.requireNonNull(riskLevel, "riskLevel must not be null");
        Objects.requireNonNull(
                processingStatus,
                "processingStatus must not be null"
        );
        Objects.requireNonNull(
                riskResponseOutcome,
                "riskResponseOutcome must not be null"
        );
        boolean approvedCombination = switch (riskLevel) {
            case LOW -> processingStatus
                    == TransactionProcessingStatus.APPROVED
                    && riskResponseOutcome == RiskResponseOutcome.APPROVED
                    && caseId == null
                    && !caseCreated;
            case MEDIUM -> processingStatus
                    == TransactionProcessingStatus.APPROVED
                    && riskResponseOutcome
                    == RiskResponseOutcome.APPROVED_WITH_MONITORING
                    && caseId == null
                    && !caseCreated;
            case HIGH -> processingStatus
                    == TransactionProcessingStatus.ADDITIONAL_AUTH_REQUIRED
                    && riskResponseOutcome
                    == RiskResponseOutcome.ADDITIONAL_AUTH_REQUIRED
                    && caseId != null;
            case CRITICAL -> processingStatus
                    == TransactionProcessingStatus.HELD
                    && riskResponseOutcome == RiskResponseOutcome.HELD
                    && caseId != null;
        };
        if (!approvedCombination) {
            throw new IllegalArgumentException(
                    "Risk response finalization result has an invalid "
                            + "combination"
            );
        }
    }
}
