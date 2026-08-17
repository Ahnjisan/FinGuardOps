package com.aifds.backend.externalrisk.domain;

import java.util.Objects;

public final class ExternalRiskLookupException extends RuntimeException {

    private final ExternalRiskFailureCategory category;

    public ExternalRiskLookupException(ExternalRiskFailureCategory category) {
        this(category, null);
    }

    public ExternalRiskLookupException(
            ExternalRiskFailureCategory category,
            Throwable cause
    ) {
        super(messageFor(category), cause);
        this.category = Objects.requireNonNull(category, "category must not be null");
    }

    public ExternalRiskFailureCategory category() {
        return category;
    }

    private static String messageFor(ExternalRiskFailureCategory category) {
        Objects.requireNonNull(category, "category must not be null");
        return switch (category) {
            case TIMEOUT -> "External Risk lookup timed out";
            case UNAVAILABLE -> "External Risk provider is unavailable";
            case INVALID_REQUEST -> "External Risk lookup request is invalid";
            case UNSUPPORTED_CAPABILITY ->
                    "External Risk lookup capability is unsupported";
            case INVALID_RESPONSE -> "External Risk provider response is invalid";
            case TRANSFORMATION_ERROR ->
                    "External Risk response transformation failed";
        };
    }
}
