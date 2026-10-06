package com.aifds.backend.idempotency.service;

import com.aifds.backend.idempotency.entity.IdempotencyRecord;
import com.aifds.backend.idempotency.repository.IdempotencyRecordRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IdempotencyClaimWriter {

    private final IdempotencyRecordRepository idempotencyRecordRepository;
    private final TransactionIntakeMaintenanceGate maintenanceGate;

    @Autowired
    public IdempotencyClaimWriter(
            IdempotencyRecordRepository idempotencyRecordRepository,
            TransactionIntakeMaintenanceGate maintenanceGate
    ) {
        this.idempotencyRecordRepository = idempotencyRecordRepository;
        this.maintenanceGate = maintenanceGate;
    }

    public IdempotencyClaimWriter(IdempotencyRecordRepository idempotencyRecordRepository) {
        this(idempotencyRecordRepository, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long createInProgress(
            String operationScope,
            String idempotencyKey,
            String requestFingerprint
    ) {
        if (maintenanceGate != null) {
            maintenanceGate.requireOpen();
        }
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
