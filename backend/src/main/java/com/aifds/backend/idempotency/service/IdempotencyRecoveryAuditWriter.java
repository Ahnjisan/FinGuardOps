package com.aifds.backend.idempotency.service;

import com.aifds.backend.audit.entity.AuditActorType;
import com.aifds.backend.common.time.DatabaseTransactionTimestampProvider;
import com.aifds.backend.idempotency.entity.IdempotencyRecoveryAuditLog;
import com.aifds.backend.idempotency.entity.IdempotencyRecoveryAuditResult;
import com.aifds.backend.idempotency.repository.IdempotencyRecoveryAuditLogRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class IdempotencyRecoveryAuditWriter {

    private final IdempotencyRecoveryAuditLogRepository auditRepository;
    private final DatabaseTransactionTimestampProvider timestampProvider;

    public IdempotencyRecoveryAuditWriter(
            IdempotencyRecoveryAuditLogRepository auditRepository,
            DatabaseTransactionTimestampProvider timestampProvider
    ) {
        this.auditRepository = auditRepository;
        this.timestampProvider = timestampProvider;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void writeInternalFailure(
            long idempotencyRecordId,
            UUID transactionId,
            AuditActorType actorType,
            String actorId
    ) {
        Instant attemptedAt = timestampProvider.currentTransactionTimestamp();
        auditRepository.insert(IdempotencyRecoveryAuditLog.create(
                idempotencyRecordId,
                transactionId,
                actorType,
                actorId,
                IdempotencyRecoveryDecision.INTERNAL_FAILURE,
                IdempotencyRecoveryAuditResult.FAILED,
                attemptedAt
        ));
    }
}
