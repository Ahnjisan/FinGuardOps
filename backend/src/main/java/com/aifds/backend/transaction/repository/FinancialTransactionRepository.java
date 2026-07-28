package com.aifds.backend.transaction.repository;

import com.aifds.backend.transaction.entity.FinancialTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface FinancialTransactionRepository extends JpaRepository<FinancialTransaction, Long> {

    Optional<FinancialTransaction> findByTransactionId(UUID transactionId);
}
