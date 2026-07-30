package com.aifds.backend.transaction.service;

import java.util.Objects;
import java.util.UUID;

public sealed interface TransactionIntakeResult {

    record Received(
            TransactionIntakeSnapshot snapshot,
            int httpStatus
    )
            implements TransactionIntakeResult {

        public Received {
            Objects.requireNonNull(snapshot, "snapshot must not be null");
            if (httpStatus
                    != TransactionIntakeSnapshotEnvelopeCodec.SUPPORTED_HTTP_STATUS) {
                throw new IllegalArgumentException(
                        "New transaction intake HTTP status must be 201"
                );
            }
        }
    }

    record KeyConflict() implements TransactionIntakeResult {
    }

    record InProgress() implements TransactionIntakeResult {
    }

    record CompletedReplay(
            TransactionIntakeSnapshot snapshot,
            int httpStatus
    )
            implements TransactionIntakeResult {

        public CompletedReplay {
            Objects.requireNonNull(snapshot, "snapshot must not be null");
            if (httpStatus != 200 && httpStatus != 201) {
                throw new IllegalArgumentException(
                        "Completed replay HTTP status must be 200 or 201"
                );
            }
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
