package com.aifds.backend.fraudcase.exception;

public class FraudCaseNotFoundException extends RuntimeException {

    public FraudCaseNotFoundException() {
        super("Fraud case was not found");
    }
}
