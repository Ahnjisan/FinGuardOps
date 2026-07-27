package com.aifds.backend.transaction.entity;

public enum TransactionProcessingStatus {
    RECEIVED,
    ANALYZING,
    ANALYZED,
    APPROVED,
    ADDITIONAL_AUTH_REQUIRED,
    HELD,
    FAILED
}
