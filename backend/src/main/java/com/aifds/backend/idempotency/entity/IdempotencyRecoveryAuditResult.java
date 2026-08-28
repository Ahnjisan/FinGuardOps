package com.aifds.backend.idempotency.entity;

public enum IdempotencyRecoveryAuditResult {
    RECOVERED,
    REJECTED,
    FAILED
}
