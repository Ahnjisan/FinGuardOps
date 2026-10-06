package com.aifds.backend.idempotency.entity;

public enum IdempotencyRecoveryAuditResult {
    RECOVERED,
    TERMINATED,
    REJECTED,
    FAILED
}
