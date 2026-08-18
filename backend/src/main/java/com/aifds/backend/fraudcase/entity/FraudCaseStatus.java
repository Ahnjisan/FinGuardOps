package com.aifds.backend.fraudcase.entity;

public enum FraudCaseStatus {
    OPEN,
    IN_REVIEW,
    ADDITIONAL_INFORMATION_REQUIRED,
    CLOSED;

    public boolean isActive() {
        return this != CLOSED;
    }
}
