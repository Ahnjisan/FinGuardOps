package com.aifds.backend.fraudcase.exception;

public class FraudCaseQueryTimeoutException extends RuntimeException {

    public FraudCaseQueryTimeoutException(Throwable cause) {
        super("Fraud case query timed out", cause);
    }
}
