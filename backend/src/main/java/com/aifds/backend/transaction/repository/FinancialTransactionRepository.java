package com.aifds.backend.transaction.repository;

import com.aifds.backend.transaction.entity.FinancialTransaction;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;
import java.time.Instant;
import java.util.List;

import java.util.Optional;
import java.util.UUID;

public interface FinancialTransactionRepository
        extends JpaRepository<FinancialTransaction, Long>,
        JpaSpecificationExecutor<FinancialTransaction> {

    Optional<FinancialTransaction> findByTransactionId(UUID transactionId);

    @Query("""
            SELECT transaction.transactionId
            FROM FinancialTransaction transaction
            WHERE transaction.externalCustomerRef = :customerRef
              AND transaction.recipientAccountRef = :recipientRef
              AND transaction.transactionId <> :currentTransactionId
              AND transaction.processingStatus =
                  com.aifds.backend.transaction.entity.TransactionProcessingStatus.APPROVED
              AND transaction.transactionType IN (
                  com.aifds.backend.transaction.entity.TransactionType.ACCOUNT_TRANSFER,
                  com.aifds.backend.transaction.entity.TransactionType.OPEN_BANKING_TRANSFER)
              AND transaction.occurredAt < :cutoff
              AND transaction.createdAt <= :cutoff
            ORDER BY transaction.occurredAt DESC, transaction.id DESC
            """)
    List<UUID> findPriorApprovedRecipientTransfers(
            @Param("customerRef") String customerRef,
            @Param("recipientRef") String recipientRef,
            @Param("currentTransactionId") UUID currentTransactionId,
            @Param("cutoff") Instant cutoff,
            Pageable pageable
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT transaction
            FROM FinancialTransaction transaction
            WHERE transaction.transactionId = :transactionId
            """)
    Optional<FinancialTransaction> findByTransactionIdForUpdate(
            @Param("transactionId") UUID transactionId
    );
}
