package com.aifds.backend.fraudcase.exception;

import java.util.Objects;

public class FraudCaseWorkflowException extends RuntimeException {

    public enum Reason {
        CASE_STATUS_CONFLICT,
        CASE_ASSIGNEE_CONFLICT,
        CASE_ALREADY_CLOSED,
        CONCURRENT_MODIFICATION,
        ASSIGNEE_REQUIRED,
        INCONSISTENT_CASE_DATA,
        DEPENDENCY_TIMEOUT,
        DEPENDENCY_UNAVAILABLE
    }

    private final Reason reason;

    public FraudCaseWorkflowException(Reason reason) {
        this(reason, null);
    }

    public FraudCaseWorkflowException(Reason reason, Throwable cause) {
        super("Fraud case workflow command failed", cause);
        this.reason = Objects.requireNonNull(reason, "reason must not be null");
    }

    public Reason getReason() {
        return reason;
    }
}
