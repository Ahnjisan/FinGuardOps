package com.aifds.backend.rule.repository;

import com.aifds.backend.rule.entity.RuleVersion;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RuleVersionRepository
        extends JpaRepository<RuleVersion, Long> {

    Optional<RuleVersion> findByRuleVersionId(UUID ruleVersionId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT version
            FROM RuleVersion version
            WHERE version.ruleVersionId = :ruleVersionId
            """)
    Optional<RuleVersion> findByRuleVersionIdForUpdate(
            @Param("ruleVersionId") UUID ruleVersionId
    );

    Optional<RuleVersion> findByFraudRule_RuleCodeAndVersionNumber(
            String ruleCode,
            int versionNumber
    );

    List<RuleVersion>
    findAllByFraudRule_RuleCodeOrderByVersionNumberDesc(String ruleCode);

    @Query("""
            SELECT version
            FROM RuleVersion version
            JOIN version.fraudRule rule
            WHERE rule.ruleCode = :ruleCode
              AND rule.lifecycleStatus =
                  com.aifds.backend.rule.entity.FraudRuleLifecycleStatus.ACTIVE
              AND version.status =
                  com.aifds.backend.rule.entity.RuleVersionStatus.PUBLISHED
              AND version.effectiveFrom <= :asOf
              AND (
                  version.effectiveTo IS NULL
                  OR :asOf < version.effectiveTo
              )
            """)
    Optional<RuleVersion> findExecutableVersion(
            @Param("ruleCode") String ruleCode,
            @Param("asOf") Instant asOf
    );

    @Query("""
            SELECT COUNT(version)
            FROM RuleVersion version
            WHERE version.fraudRule.id = :fraudRulePk
              AND version.status =
                  com.aifds.backend.rule.entity.RuleVersionStatus.PUBLISHED
              AND version.effectiveFrom < :effectiveTo
              AND (version.effectiveTo IS NULL
                  OR version.effectiveTo > :effectiveFrom)
            """)
    long countOverlappingPublishedVersionsInBoundedPeriod(
            @Param("fraudRulePk") long fraudRulePk,
            @Param("effectiveFrom") Instant effectiveFrom,
            @Param("effectiveTo") Instant effectiveTo
    );

    @Query("""
            SELECT COUNT(version)
            FROM RuleVersion version
            WHERE version.fraudRule.id = :fraudRulePk
              AND version.status =
                  com.aifds.backend.rule.entity.RuleVersionStatus.PUBLISHED
              AND (version.effectiveTo IS NULL
                  OR version.effectiveTo > :effectiveFrom)
            """)
    long countOverlappingPublishedVersionsInOpenEndedPeriod(
            @Param("fraudRulePk") long fraudRulePk,
            @Param("effectiveFrom") Instant effectiveFrom
    );
}
