package com.aifds.backend.transaction.service;

import com.aifds.backend.transaction.entity.TransactionProcessingStatus;

import java.util.Objects;
import java.util.UUID;

public sealed interface TransactionIntakeResult {

    record Received(
            UUID transactionId,
            long idempotencyRecordId
    ) implements TransactionIntakeResult {

        public Received {
            Objects.requireNonNull(transactionId, "transactionId must not be null");
            if (idempotencyRecordId <= 0) {
                throw new IllegalArgumentException(
                        "idempotencyRecordId must be positive"
                );
            }
        }

        public TransactionProcessingStatus processingStatus() {
            return TransactionProcessingStatus.RECEIVED;
        }
    }

    record KeyConflict() implements TransactionIntakeResult {
    }

    record InProgress() implements TransactionIntakeResult {
    }

    record CompletedReplay(
            String responseSnapshotJson
    ) implements TransactionIntakeResult {

        public CompletedReplay {
            Objects.requireNonNull(
                    responseSnapshotJson,
                    "responseSnapshotJson must not be null"
            );
        }
    }

    record PreviousFailure(
            String failureCode
    ) implements TransactionIntakeResult {

        public PreviousFailure {
            Objects.requireNonNull(failureCode, "failureCode must not be null");
        }
    }

    record DuplicateTransaction(
            UUID transactionId
    ) implements TransactionIntakeResult {

        public DuplicateTransaction {
            Objects.requireNonNull(transactionId, "transactionId must not be null");
        }

        public String failureCode() {
            return TransactionIntakeService.DUPLICATE_TRANSACTION;
        }
    }
}
