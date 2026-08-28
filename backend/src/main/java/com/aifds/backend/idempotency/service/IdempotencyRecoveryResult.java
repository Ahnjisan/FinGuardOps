package com.aifds.backend.idempotency.service;

import com.aifds.backend.idempotency.entity.IdempotencyRecoveryAuditResult;

import java.util.Objects;
import java.util.UUID;

public record IdempotencyRecoveryResult(
        long idempotencyRecordId,
        UUID transactionId,
        IdempotencyRecoveryDecision decision,
        IdempotencyRecoveryAuditResult auditResult
) {

    public IdempotencyRecoveryResult {
        if (idempotencyRecordId < 1) {
            throw new IllegalArgumentException(
                    "idempotencyRecordId must be positive"
            );
        }
        Objects.requireNonNull(decision, "decision must not be null");
        Objects.requireNonNull(auditResult, "auditResult must not be null");
        boolean valid = decision
                == IdempotencyRecoveryDecision.RECOVERABLE_COMPLETION_GAP
                ? auditResult == IdempotencyRecoveryAuditResult.RECOVERED
                : decision != IdempotencyRecoveryDecision.INTERNAL_FAILURE
                && auditResult == IdempotencyRecoveryAuditResult.REJECTED;
        if (!valid) {
            throw new IllegalArgumentException(
                    "decision and auditResult do not match"
            );
        }
    }

    static IdempotencyRecoveryResult recovered(
            long recordId,
            UUID transactionId
    ) {
        return new IdempotencyRecoveryResult(
                recordId,
                transactionId,
                IdempotencyRecoveryDecision.RECOVERABLE_COMPLETION_GAP,
                IdempotencyRecoveryAuditResult.RECOVERED
        );
    }

    static IdempotencyRecoveryResult rejected(
            long recordId,
            UUID transactionId,
            IdempotencyRecoveryDecision decision
    ) {
        return new IdempotencyRecoveryResult(
                recordId,
                transactionId,
                decision,
                IdempotencyRecoveryAuditResult.REJECTED
        );
    }
}
