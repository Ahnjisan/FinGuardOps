package com.aifds.backend.dashboard;

public final class FdsDashboardValidationException extends RuntimeException {
    public FdsDashboardValidationException() {
        super("Invalid FDS dashboard range");
    }
}
