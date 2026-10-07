package com.aifds.backend.aireport.event;

/** Only a failure proven to occur before the execution claim may use this category. */
public final class PreClaimTransientException extends RuntimeException {
    public PreClaimTransientException() {
        super("AI report worker unavailable before claim");
    }
}
