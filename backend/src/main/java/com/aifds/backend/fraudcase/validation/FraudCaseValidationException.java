package com.aifds.backend.fraudcase.validation;

import java.util.Objects;

public class FraudCaseValidationException extends RuntimeException {

    private final FraudCaseValidationType type;
    private final String field;
    private final String code;
    private final String reason;

    public FraudCaseValidationException(
            FraudCaseValidationType type,
            String field,
            String code,
            String reason
    ) {
        super("Fraud case request validation failed");
        this.type = Objects.requireNonNull(type, "type must not be null");
        this.field = Objects.requireNonNull(field, "field must not be null");
        this.code = Objects.requireNonNull(code, "code must not be null");
        this.reason = Objects.requireNonNull(reason, "reason must not be null");
    }

    public FraudCaseValidationType getType() {
        return type;
    }

    public String getField() {
        return field;
    }

    public String getCode() {
        return code;
    }

    public String getReason() {
        return reason;
    }
}
