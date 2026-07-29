package com.aifds.backend.transaction.exception;

public class TransactionQueryTimeoutException extends RuntimeException {

    private static final String SAFE_MESSAGE = "Transaction query timed out";

    public TransactionQueryTimeoutException(Throwable cause) {
        super(SAFE_MESSAGE, cause);
    }
}
