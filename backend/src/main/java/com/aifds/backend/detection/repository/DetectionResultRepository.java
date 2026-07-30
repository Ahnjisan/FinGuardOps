package com.aifds.backend.detection.repository;

import com.aifds.backend.detection.entity.DetectionResult;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DetectionResultRepository
        extends JpaRepository<DetectionResult, Long> {

    Optional<DetectionResult> findByDetectionResultId(UUID detectionResultId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT result
            FROM DetectionResult result
            WHERE result.detectionResultId = :detectionResultId
            """)
    Optional<DetectionResult> findByDetectionResultIdForUpdate(
            @Param("detectionResultId") UUID detectionResultId
    );

    @Query("""
            SELECT COALESCE(MAX(result.detectionResultVersion), 0)
            FROM DetectionResult result
            WHERE result.financialTransaction.id = :transactionPk
            """)
    int findMaximumVersionByTransactionPk(
            @Param("transactionPk") long transactionPk
    );

    List<DetectionResult>
    findAllByFinancialTransaction_TransactionIdOrderByDetectionResultVersionDesc(
            UUID transactionId
    );
}
