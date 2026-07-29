package com.aifds.backend.transaction.exception;

public class InvalidTransactionIntakeSnapshotException
        extends RuntimeException {

    private static final String SAFE_MESSAGE =
            "Stored transaction intake snapshot is invalid";

    public InvalidTransactionIntakeSnapshotException() {
        super(SAFE_MESSAGE);
    }

    public InvalidTransactionIntakeSnapshotException(Throwable cause) {
        super(SAFE_MESSAGE, cause);
    }
}
