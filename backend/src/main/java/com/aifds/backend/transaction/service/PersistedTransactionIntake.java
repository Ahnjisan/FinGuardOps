package com.aifds.backend.transaction.service;

import com.aifds.backend.transaction.entity.TransactionProcessingStatus;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record PersistedTransactionIntake(
        UUID transactionId,
        TransactionProcessingStatus processingStatus,
        Instant createdAt
) {

    public PersistedTransactionIntake {
        Objects.requireNonNull(transactionId, "transactionId must not be null");
        Objects.requireNonNull(
                processingStatus,
                "processingStatus must not be null"
        );
        Objects.requireNonNull(createdAt, "createdAt must not be null");
    }
}
