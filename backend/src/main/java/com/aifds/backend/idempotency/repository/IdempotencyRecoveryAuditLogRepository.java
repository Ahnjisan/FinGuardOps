package com.aifds.backend.idempotency.repository;

import com.aifds.backend.idempotency.entity.IdempotencyRecoveryAuditLog;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;

import java.util.Objects;

@Repository
public class IdempotencyRecoveryAuditLogRepository {

    @PersistenceContext
    private EntityManager entityManager;

    public void insert(IdempotencyRecoveryAuditLog auditLog) {
        entityManager.persist(Objects.requireNonNull(
                auditLog,
                "auditLog must not be null"
        ));
        entityManager.flush();
    }
}
