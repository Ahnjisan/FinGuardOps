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
        boolean valid = switch (auditResult) {
            case RECOVERED -> decision
                    == IdempotencyRecoveryDecision.RECOVERABLE_COMPLETION_GAP
                    || decision == IdempotencyRecoveryDecision.ANALYZED_FINALIZED;
            case TERMINATED -> switch (decision) {
                case UNLINKED_CLAIM_TERMINATED, RECEIVED_TERMINATED,
                        ANALYZING_TERMINATED,
                        CONFIRMED_FAILURE_TERMINATED -> true;
                default -> false;
            };
            case REJECTED -> decision
                    != IdempotencyRecoveryDecision.RECOVERABLE_COMPLETION_GAP
                    && decision != IdempotencyRecoveryDecision.ANALYZED_FINALIZED
                    && decision != IdempotencyRecoveryDecision.UNLINKED_CLAIM_TERMINATED
                    && decision != IdempotencyRecoveryDecision.RECEIVED_TERMINATED
                    && decision != IdempotencyRecoveryDecision.ANALYZING_TERMINATED
                    && decision != IdempotencyRecoveryDecision.CONFIRMED_FAILURE_TERMINATED
                    && decision != IdempotencyRecoveryDecision.INTERNAL_FAILURE;
            case FAILED -> decision == IdempotencyRecoveryDecision.INTERNAL_FAILURE;
        };
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

    static IdempotencyRecoveryResult changed(
            long recordId, UUID transactionId,
            IdempotencyRecoveryDecision decision,
            IdempotencyRecoveryAuditResult auditResult
    ) {
        return new IdempotencyRecoveryResult(recordId, transactionId,
                decision, auditResult);
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
