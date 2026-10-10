package com.aifds.backend.scenario.mock;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MockFactRepository extends JpaRepository<MockFact, Long> {
    Optional<MockFact> findByFactId(UUID factId);

    @Query("""
            SELECT fact FROM MockFact fact
            WHERE fact.externalCustomerRef = :customerRef
              AND fact.factType IN :types
              AND fact.occurredAt <= :cutoff
              AND fact.createdAt <= :cutoff
              AND (fact.occurredAt >= :recentFrom
                OR (fact.factType IN (
                    com.aifds.backend.scenario.mock.MockFactType.TRANSFER_HISTORY_COMPLETE,
                    com.aifds.backend.scenario.mock.MockFactType.DEVICE_HISTORY_COMPLETE)
                    AND fact.coverageThrough >= :cutoff))
            ORDER BY fact.occurredAt DESC, fact.factId ASC
            """)
    List<MockFact> findAsOf(@Param("customerRef") String customerRef,
                            @Param("types") List<MockFactType> types,
                            @Param("cutoff") Instant cutoff,
                            @Param("recentFrom") Instant recentFrom,
                            Pageable pageable);
}
