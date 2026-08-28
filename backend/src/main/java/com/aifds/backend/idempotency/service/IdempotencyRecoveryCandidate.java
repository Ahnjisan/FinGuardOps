package com.aifds.backend.idempotency.service;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record IdempotencyRecoveryCandidate(
        long idempotencyRecordId,
        UUID transactionId,
        Instant updatedAt
) {

    public IdempotencyRecoveryCandidate {
        if (idempotencyRecordId < 1) {
            throw new IllegalArgumentException(
                    "idempotencyRecordId must be positive"
            );
        }
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    }
}
