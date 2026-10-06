package com.aifds.backend.idempotency.service;

import com.aifds.backend.idempotency.repository.TransactionIntakeMaintenanceGateRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransactionIntakeMaintenanceGate {

    private final TransactionIntakeMaintenanceGateRepository repository;

    public TransactionIntakeMaintenanceGate(TransactionIntakeMaintenanceGateRepository repository) {
        this.repository = repository;
    }

    public boolean isOpen() {
        return repository.isOpen();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void requireOpen() {
        if (!repository.isOpenForShare()) {
            throw new ClosedException();
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void requireClosed() {
        if (repository.isOpenForShare()) {
            throw new IllegalStateException("Maintenance gate is open");
        }
    }

    public static final class ClosedException extends RuntimeException {
        public ClosedException() {
            super("Transaction intake maintenance gate is closed");
        }
    }
}
