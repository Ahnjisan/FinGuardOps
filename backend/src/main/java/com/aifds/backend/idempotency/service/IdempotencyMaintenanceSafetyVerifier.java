package com.aifds.backend.idempotency.service;

import com.aifds.backend.idempotency.repository.TransactionIntakeMaintenanceGateRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IdempotencyMaintenanceSafetyVerifier {

    private final TransactionIntakeMaintenanceGate gate;
    private final TransactionIntakeMaintenanceGateRepository repository;

    public IdempotencyMaintenanceSafetyVerifier(
            TransactionIntakeMaintenanceGate gate,
            TransactionIntakeMaintenanceGateRepository repository
    ) {
        this.gate = gate;
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void close() {
        repository.setClosed(true);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void open() {
        repository.setClosed(false);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean isSafeForReconciliation() {
        return !repository.isOpenForShare()
                && repository.activeWebSessions() == 0;
    }
}
