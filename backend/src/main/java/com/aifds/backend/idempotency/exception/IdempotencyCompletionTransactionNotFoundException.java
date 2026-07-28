package com.aifds.backend.idempotency.exception;

import java.util.UUID;

public class IdempotencyCompletionTransactionNotFoundException extends RuntimeException {

    public IdempotencyCompletionTransactionNotFoundException(UUID transactionId) {
        super("Financial transaction for idempotency completion not found: " + transactionId);
    }
}
