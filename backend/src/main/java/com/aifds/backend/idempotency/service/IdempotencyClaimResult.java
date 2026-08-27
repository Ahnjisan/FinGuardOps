package com.aifds.backend.idempotency.service;

import java.time.Instant;
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

    record FailedWithSnapshot(
            String failureCode,
            String responseSnapshotJson,
            Instant finishedAt
    ) implements IdempotencyClaimResult {

        public FailedWithSnapshot {
            Objects.requireNonNull(failureCode, "failureCode must not be null");
            Objects.requireNonNull(
                    responseSnapshotJson,
                    "responseSnapshotJson must not be null"
            );
            Objects.requireNonNull(finishedAt, "finishedAt must not be null");
        }
    }
}
