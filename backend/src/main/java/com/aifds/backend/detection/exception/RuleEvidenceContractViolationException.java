package com.aifds.backend.detection.exception;

public class RuleEvidenceContractViolationException
        extends IllegalStateException {

    public RuleEvidenceContractViolationException(String message) {
        super(message);
    }

    public RuleEvidenceContractViolationException(
            String message,
            Throwable cause
    ) {
        super(message, cause);
    }
}
