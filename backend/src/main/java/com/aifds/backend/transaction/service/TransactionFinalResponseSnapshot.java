package com.aifds.backend.transaction.service;

import com.aifds.backend.detection.entity.RiskLevel;
import com.aifds.backend.transaction.entity.RiskResponseOutcome;
import com.aifds.backend.transaction.entity.TransactionProcessingStatus;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record TransactionFinalResponseSnapshot(
        UUID transactionId,
        TransactionProcessingStatus processingStatus,
        RiskLevel riskLevel,
        RiskResponseOutcome riskResponseOutcome,
        UUID adoptedDetectionResultId,
        UUID caseId,
        Instant createdAt
) {

    public TransactionFinalResponseSnapshot {
        Objects.requireNonNull(
                transactionId,
                "transactionId must not be null"
        );
        Objects.requireNonNull(
                processingStatus,
                "processingStatus must not be null"
        );
        Objects.requireNonNull(riskLevel, "riskLevel must not be null");
        Objects.requireNonNull(
                riskResponseOutcome,
                "riskResponseOutcome must not be null"
        );
        Objects.requireNonNull(
                adoptedDetectionResultId,
                "adoptedDetectionResultId must not be null"
        );
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        requireUuidV4(transactionId, "transactionId");
        requireUuidV4(
                adoptedDetectionResultId,
                "adoptedDetectionResultId"
        );
        if (caseId != null) {
            requireUuidV4(caseId, "caseId");
        }

        boolean validCombination = switch (riskLevel) {
            case LOW -> processingStatus
                    == TransactionProcessingStatus.APPROVED
                    && riskResponseOutcome == RiskResponseOutcome.APPROVED
                    && caseId == null;
            case MEDIUM -> processingStatus
                    == TransactionProcessingStatus.APPROVED
                    && riskResponseOutcome
                    == RiskResponseOutcome.APPROVED_WITH_MONITORING
                    && caseId == null;
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
        if (!validCombination) {
            throw new IllegalArgumentException(
                    "Final response snapshot has an invalid risk combination"
            );
        }
    }

    public TransactionFinalResponseSnapshot(
            RiskResponseFinalizationResult finalizationResult,
            Instant createdAt
    ) {
        this(
                requireResult(finalizationResult).transactionId(),
                finalizationResult.processingStatus(),
                finalizationResult.riskLevel(),
                finalizationResult.riskResponseOutcome(),
                finalizationResult.adoptedDetectionResultId(),
                finalizationResult.caseId(),
                createdAt
        );
    }

    public TransactionIntakeSnapshot toTransactionIntakeSnapshot() {
        return new TransactionIntakeSnapshot(
                transactionId,
                processingStatus,
                riskLevel.name(),
                riskResponseOutcome.name(),
                adoptedDetectionResultId.toString(),
                caseId == null ? null : caseId.toString(),
                createdAt
        );
    }

    private static RiskResponseFinalizationResult requireResult(
            RiskResponseFinalizationResult finalizationResult
    ) {
        return Objects.requireNonNull(
                finalizationResult,
                "finalizationResult must not be null"
        );
    }

    private static void requireUuidV4(UUID value, String field) {
        if (value.version() != 4 || value.variant() != 2) {
            throw new IllegalArgumentException(field + " must be a UUID v4");
        }
    }
}
