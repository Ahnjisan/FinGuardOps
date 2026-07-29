package com.aifds.backend.behavior.exception;

public class BehaviorEventConcurrentResultNotFoundException
        extends RuntimeException {

    private static final String SAFE_MESSAGE =
            "Concurrent behavior event result was not found";

    public BehaviorEventConcurrentResultNotFoundException() {
        super(SAFE_MESSAGE);
    }
}
