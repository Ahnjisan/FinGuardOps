package com.aifds.backend.idempotency.service;

import com.aifds.backend.audit.entity.AuditActorType;
import com.aifds.backend.common.time.DatabaseTransactionTimestampProvider;
import com.aifds.backend.idempotency.entity.IdempotencyProcessingStatus;
import com.aifds.backend.idempotency.entity.IdempotencyRecoveryAuditLog;
import com.aifds.backend.idempotency.repository.IdempotencyRecordRepository;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Service
public class IdempotencyRecoveryService {

    public static final Duration DEFAULT_THRESHOLD = Duration.ofMinutes(30);
    public static final Duration MIN_THRESHOLD = Duration.ofMinutes(5);
    public static final Duration MAX_THRESHOLD = Duration.ofDays(7);
    public static final int DEFAULT_PAGE_SIZE = 50;
    public static final int MIN_PAGE_SIZE = 1;
    public static final int MAX_PAGE_SIZE = 100;

    private final IdempotencyRecordRepository idempotencyRecordRepository;
    private final DatabaseTransactionTimestampProvider timestampProvider;
    private final IdempotencyRecoveryTransaction recoveryTransaction;
    private final IdempotencyRecoveryAuditWriter auditWriter;

    public IdempotencyRecoveryService(
            IdempotencyRecordRepository idempotencyRecordRepository,
            DatabaseTransactionTimestampProvider timestampProvider,
            IdempotencyRecoveryTransaction recoveryTransaction,
            IdempotencyRecoveryAuditWriter auditWriter
    ) {
        this.idempotencyRecordRepository = idempotencyRecordRepository;
        this.timestampProvider = timestampProvider;
        this.recoveryTransaction = recoveryTransaction;
        this.auditWriter = auditWriter;
    }

    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.READ_COMMITTED,
            readOnly = true
    )
    public List<IdempotencyRecoveryCandidate> findLongRunningCandidates() {
        return findLongRunningCandidates(
                DEFAULT_THRESHOLD,
                DEFAULT_PAGE_SIZE
        );
    }

    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.READ_COMMITTED,
            readOnly = true
    )
    public List<IdempotencyRecoveryCandidate> findLongRunningCandidates(
            Duration threshold,
            int pageSize
    ) {
        Duration validatedThreshold = validateThreshold(threshold);
        int validatedPageSize = validatePageSize(pageSize);
        Instant databaseNow = timestampProvider.currentTransactionTimestamp();
        Instant cutoff = databaseNow.minus(validatedThreshold);
        return List.copyOf(idempotencyRecordRepository
                .findRecoveryCandidates(
                        IdempotencyService.TRANSACTION_CREATE_OPERATION_SCOPE,
                        IdempotencyProcessingStatus.IN_PROGRESS,
                        cutoff,
                        Limit.of(validatedPageSize)
                ));
    }

    public IdempotencyRecoveryResult recover(
            long idempotencyRecordId,
            AuditActorType actorType,
            String actorId
    ) {
        if (idempotencyRecordId < 1) {
            throw new IllegalArgumentException(
                    "idempotencyRecordId must be positive"
            );
        }
        IdempotencyRecoveryAuditLog.validateActor(actorType, actorId);
        try {
            return recoveryTransaction.recover(
                    idempotencyRecordId,
                    actorType,
                    actorId
            );
        } catch (RuntimeException original) {
            try {
                auditWriter.writeInternalFailure(
                        idempotencyRecordId,
                        null,
                        actorType,
                        actorId
                );
            } catch (RuntimeException auditFailure) {
                if (auditFailure != original) {
                    original.addSuppressed(auditFailure);
                }
            }
            throw original;
        }
    }

    private Duration validateThreshold(Duration threshold) {
        if (threshold == null
                || threshold.compareTo(MIN_THRESHOLD) < 0
                || threshold.compareTo(MAX_THRESHOLD) > 0) {
            throw new IllegalArgumentException(
                    "threshold must be between 5 minutes and 7 days"
            );
        }
        return threshold;
    }

    private int validatePageSize(int pageSize) {
        if (pageSize < MIN_PAGE_SIZE || pageSize > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException(
                    "pageSize must be between 1 and 100"
            );
        }
        return pageSize;
    }
}
