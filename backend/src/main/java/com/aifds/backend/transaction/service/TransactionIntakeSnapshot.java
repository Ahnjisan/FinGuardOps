package com.aifds.backend.transaction.service;

import com.aifds.backend.transaction.entity.TransactionProcessingStatus;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record TransactionIntakeSnapshot(
        UUID transactionId,
        TransactionProcessingStatus processingStatus,
        String riskLevel,
        String riskResponseOutcome,
        String adoptedDetectionResultId,
        String caseId,
        Instant createdAt
) {

    public TransactionIntakeSnapshot {
        Objects.requireNonNull(transactionId, "transactionId must not be null");
        Objects.requireNonNull(
                processingStatus,
                "processingStatus must not be null"
        );
        Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    public static TransactionIntakeSnapshot received(
            PersistedTransactionIntake persisted
    ) {
        Objects.requireNonNull(persisted, "persisted must not be null");
        if (persisted.processingStatus()
                != TransactionProcessingStatus.RECEIVED) {
            throw new IllegalArgumentException(
                    "A transaction intake snapshot must have RECEIVED status"
            );
        }
        return new TransactionIntakeSnapshot(
                persisted.transactionId(),
                persisted.processingStatus(),
                null,
                null,
                null,
                null,
                persisted.createdAt()
        );
    }
}
