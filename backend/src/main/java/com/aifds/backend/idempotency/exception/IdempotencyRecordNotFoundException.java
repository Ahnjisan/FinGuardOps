package com.aifds.backend.idempotency.exception;

public class IdempotencyRecordNotFoundException extends RuntimeException {

    public IdempotencyRecordNotFoundException(long recordId) {
        super("Idempotency record not found: " + recordId);
    }
}
