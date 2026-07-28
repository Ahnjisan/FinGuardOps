package com.aifds.backend.idempotency.repository;

import com.aifds.backend.idempotency.entity.IdempotencyRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, Long> {

    Optional<IdempotencyRecord> findByOperationScopeAndIdempotencyKey(
            String operationScope,
            String idempotencyKey
    );
}
