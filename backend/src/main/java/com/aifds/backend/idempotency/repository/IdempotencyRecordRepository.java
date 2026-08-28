package com.aifds.backend.idempotency.repository;

import com.aifds.backend.idempotency.entity.IdempotencyRecord;
import com.aifds.backend.idempotency.entity.IdempotencyProcessingStatus;
import com.aifds.backend.idempotency.service.IdempotencyRecoveryCandidate;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, Long> {

    Optional<IdempotencyRecord> findByOperationScopeAndIdempotencyKey(
            String operationScope,
            String idempotencyKey
    );

    @Query("""
            SELECT new com.aifds.backend.idempotency.service.IdempotencyRecoveryCandidate(
                record.id,
                transaction.transactionId,
                record.updatedAt
            )
            FROM IdempotencyRecord record
            LEFT JOIN record.financialTransaction transaction
            WHERE record.operationScope = :operationScope
              AND record.processingStatus = :processingStatus
              AND record.updatedAt <= :cutoff
            ORDER BY record.updatedAt ASC, record.id ASC
            """)
    List<IdempotencyRecoveryCandidate> findRecoveryCandidates(
            @Param("operationScope") String operationScope,
            @Param("processingStatus")
            IdempotencyProcessingStatus processingStatus,
            @Param("cutoff") Instant cutoff,
            Limit limit
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from IdempotencyRecord r where r.id = :id")
    Optional<IdempotencyRecord> findByIdForUpdate(@Param("id") Long id);
}
