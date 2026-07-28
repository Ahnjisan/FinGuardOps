package com.aifds.backend.idempotency.exception;

import com.aifds.backend.idempotency.entity.IdempotencyProcessingStatus;

public class IdempotencyStateTransitionNotAllowedException extends RuntimeException {

    public IdempotencyStateTransitionNotAllowedException(
            IdempotencyProcessingStatus currentStatus,
            IdempotencyProcessingStatus targetStatus
    ) {
        super(
                "Idempotency state transition is not allowed: "
                        + currentStatus
                        + " -> "
                        + targetStatus
        );
    }
}
