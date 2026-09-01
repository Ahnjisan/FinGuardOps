package com.aifds.backend.fraudcase.repository;

import com.aifds.backend.fraudcase.entity.CaseTransaction;
import com.aifds.backend.fraudcase.entity.FraudCaseStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface CaseTransactionRepository
        extends JpaRepository<CaseTransaction, Long> {

    interface FraudCaseTransactionCount {

        Long getFraudCasePk();

        long getTransactionCount();
    }

    @Query("""
            SELECT caseTransaction.fraudCase.id AS fraudCasePk,
                   COUNT(caseTransaction.id) AS transactionCount
            FROM CaseTransaction caseTransaction
            WHERE caseTransaction.fraudCase.id IN :fraudCasePks
            GROUP BY caseTransaction.fraudCase.id
            """)
    List<FraudCaseTransactionCount> countByFraudCasePks(
            @Param("fraudCasePks") Collection<Long> fraudCasePks
    );

    @Query("""
            SELECT fraudCase.caseId
            FROM CaseTransaction caseTransaction
            JOIN caseTransaction.fraudCase fraudCase
            WHERE caseTransaction.financialTransaction.id = :transactionPk
              AND fraudCase.caseStatus IN :activeStatuses
            ORDER BY fraudCase.caseId
            """)
    List<UUID> findActiveCaseIdsByTransactionPk(
            @Param("transactionPk") long transactionPk,
            @Param("activeStatuses")
            Collection<FraudCaseStatus> activeStatuses
    );

    @Query("""
            SELECT caseTransaction
            FROM CaseTransaction caseTransaction
            JOIN FETCH caseTransaction.fraudCase fraudCase
            JOIN FETCH caseTransaction.financialTransaction transaction
            WHERE transaction.id = :transactionPk
            ORDER BY fraudCase.caseId ASC
            """)
    List<CaseTransaction> findAllByTransactionPk(
            @Param("transactionPk") long transactionPk
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT caseTransaction
            FROM CaseTransaction caseTransaction
            WHERE caseTransaction.financialTransaction.id = :transactionPk
              AND caseTransaction.fraudCase.caseId IN :caseIds
            ORDER BY caseTransaction.fraudCase.caseId
            """)
    List<CaseTransaction> findAllByTransactionAndCaseIdsForUpdate(
            @Param("transactionPk") long transactionPk,
            @Param("caseIds") List<UUID> caseIds
    );
}
