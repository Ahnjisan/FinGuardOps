package com.aifds.backend.transaction.validation;

import java.util.Objects;

public class TransactionValidationException extends RuntimeException {

    private final TransactionValidationType type;
    private final String field;
    private final String code;
    private final String reason;

    public TransactionValidationException(
            TransactionValidationType type,
            String field,
            String code,
            String reason
    ) {
        super(reason);
        this.type = Objects.requireNonNull(type, "type must not be null");
        this.field = Objects.requireNonNull(field, "field must not be null");
        this.code = Objects.requireNonNull(code, "code must not be null");
        this.reason = Objects.requireNonNull(reason, "reason must not be null");
    }

    public TransactionValidationType getType() {
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
