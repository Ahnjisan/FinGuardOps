package com.aifds.backend.behavior.exception;

public class BehaviorEventTransactionNotFoundException
        extends RuntimeException {

    private static final String SAFE_MESSAGE =
            "Related transaction was not found";

    public BehaviorEventTransactionNotFoundException() {
        super(SAFE_MESSAGE);
    }
}
