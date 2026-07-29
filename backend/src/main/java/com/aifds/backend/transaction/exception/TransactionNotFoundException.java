package com.aifds.backend.transaction.exception;

public class TransactionNotFoundException extends RuntimeException {

    private static final String SAFE_MESSAGE = "Transaction was not found";

    public TransactionNotFoundException() {
        super(SAFE_MESSAGE);
    }
}
