package com.aifds.backend.fraudcase.exception;

public class FraudCaseQueryUnavailableException extends RuntimeException {

    public FraudCaseQueryUnavailableException(Throwable cause) {
        super("Fraud case query repository is unavailable", cause);
    }
}
