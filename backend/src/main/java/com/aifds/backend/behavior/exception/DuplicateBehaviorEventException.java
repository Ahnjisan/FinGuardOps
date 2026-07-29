package com.aifds.backend.behavior.exception;

public class DuplicateBehaviorEventException extends RuntimeException {

    private static final String SAFE_MESSAGE =
            "Behavior event identifier is already used by another request";

    public DuplicateBehaviorEventException() {
        super(SAFE_MESSAGE);
    }
}
