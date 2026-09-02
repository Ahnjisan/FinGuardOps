package com.aifds.backend.fraudcase.exception;

import java.util.Objects;

public class InvestigationNoteException extends RuntimeException {

    public enum Reason {
        NOTE_NOT_ALLOWED,
        CONCURRENT_MODIFICATION,
        DEPENDENCY_TIMEOUT,
        DEPENDENCY_UNAVAILABLE,
        INTERNAL_FAILURE
    }

    private final Reason reason;

    public InvestigationNoteException(Reason reason) {
        this(reason, null);
    }

    public InvestigationNoteException(Reason reason, Throwable cause) {
        super("Investigation note operation failed", cause);
        this.reason = Objects.requireNonNull(reason);
    }

    public Reason getReason() { return reason; }
}
