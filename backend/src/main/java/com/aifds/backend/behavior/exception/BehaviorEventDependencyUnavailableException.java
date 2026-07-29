package com.aifds.backend.behavior.exception;

public class BehaviorEventDependencyUnavailableException
        extends RuntimeException {

    private static final String SAFE_MESSAGE =
            "Behavior event repository is unavailable";

    public BehaviorEventDependencyUnavailableException(Throwable cause) {
        super(SAFE_MESSAGE, cause);
    }
}
