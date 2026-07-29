package com.aifds.backend.transaction.repository;

import com.aifds.backend.transaction.entity.FinancialTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;
import java.util.UUID;

public interface FinancialTransactionRepository
        extends JpaRepository<FinancialTransaction, Long>,
        JpaSpecificationExecutor<FinancialTransaction> {

    Optional<FinancialTransaction> findByTransactionId(UUID transactionId);
}
