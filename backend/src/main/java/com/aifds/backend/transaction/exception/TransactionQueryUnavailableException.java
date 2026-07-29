package com.aifds.backend.transaction.exception;

public class TransactionQueryUnavailableException extends RuntimeException {

    private static final String SAFE_MESSAGE =
            "Transaction query repository is unavailable";

    public TransactionQueryUnavailableException(Throwable cause) {
        super(SAFE_MESSAGE, cause);
    }
}
