package com.aifds.backend.behavior.repository;

import com.aifds.backend.behavior.entity.BehaviorEvent;
import com.aifds.backend.behavior.entity.BehaviorEventType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface BehaviorEventRepository extends JpaRepository<BehaviorEvent, Long> {

    Optional<BehaviorEvent> findByEventId(UUID eventId);

    /**
     * Returns the first limited rule-evaluation window in fixed business order.
     * Callers must provide a non-empty {@code eventTypes} set and a paged
     * {@code Pageable} with page zero and a positive finite page size.
     */
    @Query("""
            SELECT event
            FROM BehaviorEvent event
            WHERE event.externalCustomerRef = :externalCustomerRef
              AND event.eventType IN :eventTypes
              AND event.occurredAt >= :fromInclusive
              AND event.occurredAt <= :toInclusive
            ORDER BY event.occurredAt DESC, event.eventId ASC
            """)
    List<BehaviorEvent> findForRuleEvaluation(
            @Param("externalCustomerRef") String externalCustomerRef,
            @Param("eventTypes") Set<BehaviorEventType> eventTypes,
            @Param("fromInclusive") Instant fromInclusive,
            @Param("toInclusive") Instant toInclusive,
            Pageable pageable
    );
}
