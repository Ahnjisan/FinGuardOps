package com.aifds.backend.externalrisk.exception;

public final class InvalidExternalRiskFailureSnapshotException
        extends RuntimeException {

    private static final String SAFE_MESSAGE =
            "Stored External Risk failure snapshot is invalid";

    public InvalidExternalRiskFailureSnapshotException() {
        super(SAFE_MESSAGE);
    }
}
