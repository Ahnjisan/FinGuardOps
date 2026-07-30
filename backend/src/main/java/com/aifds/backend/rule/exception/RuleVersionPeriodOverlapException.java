package com.aifds.backend.rule.exception;

public class RuleVersionPeriodOverlapException extends RuntimeException {

    public RuleVersionPeriodOverlapException() {
        super("Published rule version period overlaps an existing version");
    }
}
