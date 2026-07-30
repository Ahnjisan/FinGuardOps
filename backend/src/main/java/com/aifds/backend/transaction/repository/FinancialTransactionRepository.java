package com.aifds.backend.transaction.repository;

import com.aifds.backend.transaction.entity.FinancialTransaction;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface FinancialTransactionRepository
        extends JpaRepository<FinancialTransaction, Long>,
        JpaSpecificationExecutor<FinancialTransaction> {

    Optional<FinancialTransaction> findByTransactionId(UUID transactionId);

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
