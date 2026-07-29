package com.aifds.backend.behavior.validation;

import java.util.Objects;

public class BehaviorEventValidationException extends RuntimeException {

    private final BehaviorEventValidationType type;
    private final String field;
    private final String code;
    private final String reason;

    public BehaviorEventValidationException(
            BehaviorEventValidationType type,
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

    public BehaviorEventValidationType getType() {
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
