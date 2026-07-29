package com.aifds.backend.transaction.service;

import java.util.Objects;
import java.util.UUID;

public sealed interface TransactionIntakeResult {

    record Received(TransactionIntakeSnapshot snapshot)
            implements TransactionIntakeResult {

        public Received {
            Objects.requireNonNull(snapshot, "snapshot must not be null");
        }
    }

    record KeyConflict() implements TransactionIntakeResult {
    }

    record InProgress() implements TransactionIntakeResult {
    }

    record CompletedReplay(TransactionIntakeSnapshot snapshot)
            implements TransactionIntakeResult {

        public CompletedReplay {
            Objects.requireNonNull(snapshot, "snapshot must not be null");
        }
    }

    record PreviousFailure(
            String failureCode
    ) implements TransactionIntakeResult {
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
