package com.aifds.backend.fraudcase.repository;

import com.aifds.backend.fraudcase.entity.FraudCase;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FraudCaseRepository extends JpaRepository<FraudCase, Long> {

    Optional<FraudCase> findByCaseId(UUID caseId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT fraudCase
            FROM FraudCase fraudCase
            WHERE fraudCase.caseId IN :caseIds
            ORDER BY fraudCase.caseId
            """)
    List<FraudCase> findAllByCaseIdsForUpdate(
            @Param("caseIds") List<UUID> caseIds
    );
}
