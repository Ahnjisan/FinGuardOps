package com.aifds.backend.transaction.exception;

public class TransactionIntakeRejectedException extends RuntimeException {

    public enum Reason {
        IDEMPOTENCY_KEY_CONFLICT,
        IDEMPOTENCY_REQUEST_IN_PROGRESS,
        DUPLICATE_TRANSACTION,
        DEPENDENCY_TIMEOUT,
        INTERNAL_FAILURE
    }

    private static final String DUPLICATE_TRANSACTION =
            "DUPLICATE_TRANSACTION";
    private static final String DEPENDENCY_TIMEOUT = "DEPENDENCY_TIMEOUT";

    private final Reason reason;

    private TransactionIntakeRejectedException(Reason reason) {
        super("Transaction intake was rejected");
        this.reason = reason;
    }

    public static TransactionIntakeRejectedException keyConflict() {
        return new TransactionIntakeRejectedException(
                Reason.IDEMPOTENCY_KEY_CONFLICT
        );
    }

    public static TransactionIntakeRejectedException requestInProgress() {
        return new TransactionIntakeRejectedException(
                Reason.IDEMPOTENCY_REQUEST_IN_PROGRESS
        );
    }

    public static TransactionIntakeRejectedException duplicateTransaction() {
        return new TransactionIntakeRejectedException(
                Reason.DUPLICATE_TRANSACTION
        );
    }

    public static TransactionIntakeRejectedException previousFailure(
            String failureCode
    ) {
        if (DUPLICATE_TRANSACTION.equals(failureCode)) {
            return duplicateTransaction();
        }
        if (DEPENDENCY_TIMEOUT.equals(failureCode)) {
            return new TransactionIntakeRejectedException(
                    Reason.DEPENDENCY_TIMEOUT
            );
        }
        return new TransactionIntakeRejectedException(
                Reason.INTERNAL_FAILURE
        );
    }

    public Reason reason() {
        return reason;
    }
}
