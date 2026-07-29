package com.aifds.backend.idempotency.service;

import java.util.Objects;

public sealed interface IdempotencyClaimResult {

    record Acquired(long recordId) implements IdempotencyClaimResult {
    }

    record KeyConflict() implements IdempotencyClaimResult {
    }

    record InProgress() implements IdempotencyClaimResult {
    }

    record Completed(String responseSnapshotJson) implements IdempotencyClaimResult {

        public Completed {
            Objects.requireNonNull(
                    responseSnapshotJson,
                    "responseSnapshotJson must not be null"
            );
        }
    }

    record Failed(String failureCode) implements IdempotencyClaimResult {
    }
}
