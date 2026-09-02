package com.aifds.backend.fraudcase.validation;

import java.util.Objects;

public class InvestigationNoteValidationException extends RuntimeException {

    private final InvestigationNoteValidationType type;
    private final String field;
    private final String code;
    private final String reason;

    public InvestigationNoteValidationException(
            InvestigationNoteValidationType type,
            String field,
            String code,
            String reason
    ) {
        super("Investigation note request validation failed");
        this.type = Objects.requireNonNull(type);
        this.field = Objects.requireNonNull(field);
        this.code = Objects.requireNonNull(code);
        this.reason = Objects.requireNonNull(reason);
    }

    public InvestigationNoteValidationType getType() { return type; }
    public String getField() { return field; }
    public String getCode() { return code; }
    public String getReason() { return reason; }
}
