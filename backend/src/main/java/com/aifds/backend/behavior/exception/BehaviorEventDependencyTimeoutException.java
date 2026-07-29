package com.aifds.backend.behavior.exception;

public class BehaviorEventDependencyTimeoutException
        extends RuntimeException {

    private static final String SAFE_MESSAGE =
            "Behavior event repository operation timed out";

    public BehaviorEventDependencyTimeoutException(Throwable cause) {
        super(SAFE_MESSAGE, cause);
    }
}
