package com.aifds.backend.transaction.exception;

import com.aifds.backend.transaction.service.TransactionIntakeResult;

public class TransactionIntakeRejectedException extends RuntimeException {

    public enum Reason {
        IDEMPOTENCY_KEY_CONFLICT,
        IDEMPOTENCY_REQUEST_IN_PROGRESS,
        DUPLICATE_TRANSACTION,
        DEPENDENCY_TIMEOUT,
        DEPENDENCY_UNAVAILABLE,
        TYPED_FAILURE,
        INTERNAL_FAILURE
    }

    private static final String DUPLICATE_TRANSACTION =
            "DUPLICATE_TRANSACTION";
    private static final String DEPENDENCY_TIMEOUT = "DEPENDENCY_TIMEOUT";
    private static final String DEPENDENCY_UNAVAILABLE =
            "DEPENDENCY_UNAVAILABLE";

    private final Reason reason;
    private final Integer httpStatus;
    private final String publicCode;
    private final String publicMessage;

    private TransactionIntakeRejectedException(Reason reason) {
        this(reason, null, null, null);
    }

    private TransactionIntakeRejectedException(
            Reason reason,
            Integer httpStatus,
            String publicCode,
            String publicMessage
    ) {
        super("Transaction intake was rejected");
        this.reason = reason;
        this.httpStatus = httpStatus;
        this.publicCode = publicCode;
        this.publicMessage = publicMessage;
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
        if (DEPENDENCY_UNAVAILABLE.equals(failureCode)) {
            return dependencyUnavailable();
        }
        return new TransactionIntakeRejectedException(
                Reason.INTERNAL_FAILURE
        );
    }

    public static TransactionIntakeRejectedException dependencyUnavailable() {
        return new TransactionIntakeRejectedException(
                Reason.DEPENDENCY_UNAVAILABLE
        );
    }

    public static TransactionIntakeRejectedException typedFailure(
            int httpStatus,
            String publicCode,
            String publicMessage
    ) {
        if (httpStatus != 500 && httpStatus != 503) {
            throw new IllegalArgumentException(
                    "Typed failure HTTP status must be 500 or 503"
            );
        }
        if (publicCode == null || publicMessage == null) {
            throw new NullPointerException(
                    "Typed failure code and message must not be null"
            );
        }
        boolean dependencyFailure = httpStatus == 503
                && ("DEPENDENCY_TIMEOUT".equals(publicCode)
                || "DEPENDENCY_UNAVAILABLE".equals(publicCode))
                && TransactionIntakeResult.DEPENDENCY_MESSAGE.equals(
                publicMessage
        );
        boolean internalFailure = httpStatus == 500
                && "INTERNAL_ERROR".equals(publicCode)
                && TransactionIntakeResult.INTERNAL_ERROR_MESSAGE.equals(
                publicMessage
        );
        if (!dependencyFailure && !internalFailure) {
            throw new IllegalArgumentException(
                    "Typed failure public mapping is not approved"
            );
        }
        return new TransactionIntakeRejectedException(
                Reason.TYPED_FAILURE,
                httpStatus,
                publicCode,
                publicMessage
        );
    }

    public Reason reason() {
        return reason;
    }

    public Integer httpStatus() {
        return httpStatus;
    }

    public String publicCode() {
        return publicCode;
    }

    public String publicMessage() {
        return publicMessage;
    }
}
