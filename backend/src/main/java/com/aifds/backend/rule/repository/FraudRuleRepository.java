package com.aifds.backend.rule.repository;

import com.aifds.backend.rule.entity.FraudRule;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface FraudRuleRepository extends JpaRepository<FraudRule, Long> {

    Optional<FraudRule> findByFraudRuleId(UUID fraudRuleId);

    Optional<FraudRule> findByRuleCode(String ruleCode);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT rule
            FROM FraudRule rule
            WHERE rule.fraudRuleId = :fraudRuleId
            """)
    Optional<FraudRule> findByFraudRuleIdForUpdate(
            @Param("fraudRuleId") UUID fraudRuleId
    );
}
