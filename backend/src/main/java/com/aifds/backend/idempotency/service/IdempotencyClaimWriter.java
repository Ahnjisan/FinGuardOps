package com.aifds.backend.idempotency.service;

import com.aifds.backend.idempotency.entity.IdempotencyRecord;
import com.aifds.backend.idempotency.repository.IdempotencyRecordRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IdempotencyClaimWriter {

    private final IdempotencyRecordRepository idempotencyRecordRepository;

    public IdempotencyClaimWriter(
            IdempotencyRecordRepository idempotencyRecordRepository
    ) {
        this.idempotencyRecordRepository = idempotencyRecordRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long createInProgress(
            String operationScope,
            String idempotencyKey,
            String requestFingerprint
    ) {
        IdempotencyRecord saved = idempotencyRecordRepository.saveAndFlush(
                IdempotencyRecord.inProgress(
                        operationScope,
                        idempotencyKey,
                        requestFingerprint
                )
        );
        return saved.getId();
    }
}
